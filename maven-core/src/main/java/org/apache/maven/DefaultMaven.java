package org.apache.maven;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.api.Session;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.execution.BuildResumptionAnalyzer;
import org.apache.maven.execution.BuildResumptionDataRepository;
import org.apache.maven.execution.BuildResumptionPersistenceException;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProfileActivation;
import org.apache.maven.execution.ProjectActivation;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.graph.GraphBuilder;
import org.apache.maven.graph.ProjectSelector;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.internal.impl.DefaultSession;
import org.apache.maven.internal.impl.DefaultSessionFactory;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.internal.aether.MavenChainedWorkspaceReader;
import org.apache.maven.lifecycle.internal.ExecutionEventCatapult;
import org.apache.maven.lifecycle.internal.LifecycleStarter;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Prerequisites;
import org.apache.maven.api.model.Profile;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.Result;
import org.apache.maven.model.superpom.SuperPomProvider;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.repository.LocalRepositoryNotAccessibleException;
import org.apache.maven.session.scope.internal.SessionScope;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.WorkspaceReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;

/**
 * @author Jason van Zyl
 */
@Named
@Singleton
public class DefaultMaven
    implements Maven
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    protected ProjectBuilder projectBuilder;

    private LifecycleStarter lifecycleStarter;

    protected PlexusContainer container;

    private ExecutionEventCatapult eventCatapult;

    private LegacySupport legacySupport;

    private SessionScope sessionScope;

    private DefaultRepositorySystemSessionFactory repositorySessionFactory;

    private final GraphBuilder graphBuilder;

    private final BuildResumptionAnalyzer buildResumptionAnalyzer;

    private final BuildResumptionDataRepository buildResumptionDataRepository;

    private final SuperPomProvider superPomProvider;

    private final DefaultSessionFactory defaultSessionFactory;

    private final ProjectSelector projectSelector;

    @Inject
    @SuppressWarnings( "checkstyle:ParameterNumber" )
    public DefaultMaven(
            ProjectBuilder projectBuilder,
            LifecycleStarter lifecycleStarter,
            PlexusContainer container,
            ExecutionEventCatapult eventCatapult,
            LegacySupport legacySupport,
            SessionScope sessionScope,
            DefaultRepositorySystemSessionFactory repositorySessionFactory,
            @Named( GraphBuilder.HINT ) GraphBuilder graphBuilder,
            BuildResumptionAnalyzer buildResumptionAnalyzer,
            BuildResumptionDataRepository buildResumptionDataRepository,
            SuperPomProvider superPomProvider,
            DefaultSessionFactory defaultSessionFactory )
    {
        this.projectBuilder = projectBuilder;
        this.lifecycleStarter = lifecycleStarter;
        this.container = container;
        this.eventCatapult = eventCatapult;
        this.legacySupport = legacySupport;
        this.sessionScope = sessionScope;
        this.repositorySessionFactory = repositorySessionFactory;
        this.graphBuilder = graphBuilder;
        this.buildResumptionAnalyzer = buildResumptionAnalyzer;
        this.buildResumptionDataRepository = buildResumptionDataRepository;
        this.superPomProvider = superPomProvider;
        this.defaultSessionFactory = defaultSessionFactory;
        this.projectSelector = new ProjectSelector(); // if necessary switch to DI
    }

    @Override
    public MavenExecutionResult execute( MavenExecutionRequest request )
    {
        MavenExecutionResult result;

        try
        {
            result = doExecute( request );
        }
        catch ( OutOfMemoryError e )
        {
            result = addExceptionToResult( new DefaultMavenExecutionResult(), e );
        }
        catch ( RuntimeException e )
        {
            // TODO Hack to make the cycle detection the same for the new graph builder
            if ( e.getCause() instanceof ProjectCycleException )
            {
                result = addExceptionToResult( new DefaultMavenExecutionResult(), e.getCause() );
            }
            else
            {
                result = addExceptionToResult( new DefaultMavenExecutionResult(),
                                               new InternalErrorException( "Internal error: " + e, e ) );
            }
        }
        finally
        {
            legacySupport.setSession( null );
        }

        return result;
    }

    //
    // 1) Setup initial properties.
    //
    // 2) Validate local repository directory is accessible.
    //
    // 3) Create RepositorySystemSession.
    //
    // 4) Create MavenSession.
    //
    // 5) Execute AbstractLifecycleParticipant.afterSessionStart(session)
    //
    // 6) Get reactor projects looking for general POM errors
    //
    // 7) Create ProjectDependencyGraph using trimming which takes into account --projects and reactor mode.
    // This ensures that the projects passed into the ReactorReader are only those specified.
    //
    // 8) Create ReactorReader with the getProjectMap( projects ). NOTE that getProjectMap(projects) is the code that
    // checks for duplicate projects definitions in the build. Ideally this type of duplicate checking should be
    // part of getting the reactor projects in 6). The duplicate checking is conflated with getProjectMap(projects).
    //
    // 9) Execute AbstractLifecycleParticipant.afterProjectsRead(session)
    //
    // 10) Create ProjectDependencyGraph without trimming (as trimming was done in 7). A new topological sort is
    // required after the execution of 9) as the AbstractLifecycleParticipants are free to mutate the MavenProject
    // instances, which may change dependencies which can, in turn, affect the build order.
    //
    // 11) Execute LifecycleStarter.start()
    //
    @SuppressWarnings( "checkstyle:methodlength" )
    private MavenExecutionResult doExecute( MavenExecutionRequest request )
    {
        request.setStartTime( new Date() );

        MavenExecutionResult result = new DefaultMavenExecutionResult();

        try
        {
            validateLocalRepository( request );
        }
        catch ( LocalRepositoryNotAccessibleException e )
        {
            return addExceptionToResult( result, e );
        }

        //
        // We enter the session scope right after the MavenSession creation and before any of the
        // AbstractLifecycleParticipant lookups
        // so that @SessionScoped components can be @Injected into AbstractLifecycleParticipants.
        //
        sessionScope.enter();
        try
        {
            DefaultRepositorySystemSession repoSession =
                (DefaultRepositorySystemSession) newRepositorySession( request );
            MavenSession session = new MavenSession( container, repoSession, request, result );
            session.setSession( defaultSessionFactory.getSession( session ) );

            sessionScope.seed( MavenSession.class, session );
            sessionScope.seed( Session.class, session.getSession() );
            sessionScope.seed( DefaultSession.class, (DefaultSession) session.getSession() );

            legacySupport.setSession( session );

            return doExecute( request, session, result, repoSession );
        }
        finally
        {
            sessionScope.exit();
        }
    }

    private MavenExecutionResult doExecute( MavenExecutionRequest request, MavenSession session,
                                            MavenExecutionResult result, DefaultRepositorySystemSession repoSession )
    {
        try
        {
            afterSessionStart( session );
        }
        catch ( MavenExecutionException e )
        {
            return addExceptionToResult( result, e );
        }

        eventCatapult.fire( ExecutionEvent.Type.ProjectDiscoveryStarted, session, null );

        Result<? extends ProjectDependencyGraph> graphResult = buildGraph( session );

        if ( graphResult.hasErrors() )
        {
            return addExceptionToResult( result, graphResult.getProblems().iterator().next().getException() );
        }

        try
        {
            session.setProjectMap( getProjectMap( session.getProjects() ) );
        }
        catch ( DuplicateProjectException e )
        {
            return addExceptionToResult( result, e );
        }

        try
        {
            setupWorkspaceReader( session, repoSession );
        }
        catch ( ComponentLookupException e )
        {
            return addExceptionToResult( result, e );
        }
        repoSession.setReadOnly();
        try
        {
            afterProjectsRead( session );
        }
        catch ( MavenExecutionException e )
        {
            return addExceptionToResult( result, e );
        }

        //
        // The projects need to be topologically after the participants have run their afterProjectsRead(session)
        // because the participant is free to change the dependencies of a project which can potentially change the
        // topological order of the projects, and therefore can potentially change the build order.
        //
        // Note that participants may affect the topological order of the projects but it is
        // not expected that a participant will add or remove projects from the session.
        //

        graphResult = buildGraph( session );

        if ( graphResult.hasErrors() )
        {
            return addExceptionToResult( result, graphResult.getProblems().iterator().next().getException() );
        }

        try
        {
            if ( result.hasExceptions() )
            {
                return result;
            }

            result.setTopologicallySortedProjects( session.getProjects() );

            result.setProject( session.getTopLevelProject() );

            validatePrerequisitesForNonMavenPluginProjects( session.getProjects() );

            validateRequiredProfiles( session, request.getProfileActivation() );
            if ( session.getResult().hasExceptions() )
            {
                return result;
            }

            validateOptionalProfiles( session, request.getProfileActivation() );

            lifecycleStarter.execute( session );

            validateOptionalProjects( request, session );
            validateOptionalProfiles( session, request.getProfileActivation() );

            if ( session.getResult().hasExceptions() )
            {
                addExceptionToResult( result, session.getResult().getExceptions().get( 0 ) );
                persistResumptionData( result, session );
                return result;
            }
            else
            {
                session.getAllProjects().stream()
                        .filter( MavenProject::isExecutionRoot )
                        .findFirst()
                        .ifPresent( buildResumptionDataRepository::removeResumptionData );
            }
        }
        finally
        {
            try
            {
                afterSessionEnd( session.getProjects(), session );
            }
            catch ( MavenExecutionException e )
            {
                return addExceptionToResult( result, e );
            }
        }

        return result;
    }

    private void setupWorkspaceReader( MavenSession session, DefaultRepositorySystemSession repoSession )
        throws ComponentLookupException
    {
        // Desired order of precedence for workspace readers before querying the local artifact repositories
        List<WorkspaceReader> workspaceReaders = new ArrayList<WorkspaceReader>();
        // 1) Reactor workspace reader
        workspaceReaders.add( container.lookup( WorkspaceReader.class, ReactorReader.HINT ) );
        // 2) Repository system session-scoped workspace reader
        WorkspaceReader repoWorkspaceReader = repoSession.getWorkspaceReader();
        if ( repoWorkspaceReader != null )
        {
            workspaceReaders.add( repoWorkspaceReader );
        }
        // 3) .. n) Project-scoped workspace readers
        for ( WorkspaceReader workspaceReader : getProjectScopedExtensionComponents( session.getProjects(),
                                                                                     WorkspaceReader.class ) )
        {
            if ( workspaceReaders.contains( workspaceReader ) )
            {
                continue;
            }
            workspaceReaders.add( workspaceReader );
        }
        repoSession.setWorkspaceReader( MavenChainedWorkspaceReader.of( workspaceReaders ) );
    }

    private void afterSessionStart( MavenSession session )
        throws MavenExecutionException
    {
        // CHECKSTYLE_OFF: LineLength
        for ( AbstractMavenLifecycleParticipant listener : getExtensionComponents( Collections.emptyList(),
                                                                                   AbstractMavenLifecycleParticipant.class ) )
        // CHECKSTYLE_ON: LineLength
        {
            listener.afterSessionStart( session );
        }
    }

    private void afterProjectsRead( MavenSession session )
        throws MavenExecutionException
    {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            // CHECKSTYLE_OFF: LineLength
            for ( AbstractMavenLifecycleParticipant listener : getExtensionComponents( session.getProjects(),
                                                                                       AbstractMavenLifecycleParticipant.class ) )
            // CHECKSTYLE_ON: LineLength
            {
                Thread.currentThread().setContextClassLoader( listener.getClass().getClassLoader() );

                listener.afterProjectsRead( session );
            }
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( originalClassLoader );
        }
    }

    private void afterSessionEnd( Collection<MavenProject> projects, MavenSession session )
        throws MavenExecutionException
    {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            // CHECKSTYLE_OFF: LineLength
            for ( AbstractMavenLifecycleParticipant listener : getExtensionComponents( projects,
                                                                                       AbstractMavenLifecycleParticipant.class ) )
            // CHECKSTYLE_ON: LineLength
            {
                Thread.currentThread().setContextClassLoader( listener.getClass().getClassLoader() );

                listener.afterSessionEnd( session );
            }
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( originalClassLoader );
        }
    }

    private void persistResumptionData( MavenExecutionResult result, MavenSession session )
    {
        boolean hasLifecycleExecutionExceptions = result.getExceptions().stream()
                .anyMatch( LifecycleExecutionException.class::isInstance );

        if ( hasLifecycleExecutionExceptions )
        {
            MavenProject rootProject = session.getAllProjects().stream()
                    .filter( MavenProject::isExecutionRoot )
                    .findFirst()
                    .orElseThrow( () -> new IllegalStateException( "No project in the session is execution root" ) );

            buildResumptionAnalyzer.determineBuildResumptionData( result ).ifPresent( resumption ->
            {
                try
                {
                    buildResumptionDataRepository.persistResumptionData( rootProject, resumption );
                    result.setCanResume( true );
                }
                catch ( BuildResumptionPersistenceException e )
                {
                    logger.warn( "Could not persist build resumption data", e );
                }
            } );
        }
    }

    public RepositorySystemSession newRepositorySession( MavenExecutionRequest request )
    {
        return repositorySessionFactory.newRepositorySession( request );
    }

    private void validateLocalRepository( MavenExecutionRequest request )
        throws LocalRepositoryNotAccessibleException
    {
        File localRepoDir = request.getLocalRepositoryPath();

        logger.debug( "Using local repository at " + localRepoDir );

        localRepoDir.mkdirs();

        if ( !localRepoDir.isDirectory() )
        {
            throw new LocalRepositoryNotAccessibleException( "Could not create local repository at " + localRepoDir );
        }
    }

    private <T> Collection<T> getExtensionComponents( Collection<MavenProject> projects, Class<T> role )
    {
        Collection<T> foundComponents = new LinkedHashSet<>();

        try
        {
            foundComponents.addAll( container.lookupList( role ) );
        }
        catch ( ComponentLookupException e )
        {
            // this is just silly, lookupList should return an empty list!
            logger.warn( "Failed to lookup " + role + ": " + e.getMessage() );
        }

        foundComponents.addAll( getProjectScopedExtensionComponents( projects, role ) );

        return foundComponents;
    }

    protected <T> Collection<T> getProjectScopedExtensionComponents( Collection<MavenProject> projects, Class<T> role )
    {

        Collection<T> foundComponents = new LinkedHashSet<>();
        Collection<ClassLoader> scannedRealms = new HashSet<>();

        Thread currentThread = Thread.currentThread();
        ClassLoader originalContextClassLoader = currentThread.getContextClassLoader();
        try
        {
            for ( MavenProject project : projects )
            {
                ClassLoader projectRealm = project.getClassRealm();

                if ( projectRealm != null && scannedRealms.add( projectRealm ) )
                {
                    currentThread.setContextClassLoader( projectRealm );

                    try
                    {
                        foundComponents.addAll( container.lookupList( role ) );
                    }
                    catch ( ComponentLookupException e )
                    {
                        // this is just silly, lookupList should return an empty list!
                        logger.warn( "Failed to lookup " + role + ": " + e.getMessage() );
                    }
                }
            }
            return foundComponents;
        }
        finally
        {
            currentThread.setContextClassLoader( originalContextClassLoader );
        }
    }

    private MavenExecutionResult addExceptionToResult( MavenExecutionResult result, Throwable e )
    {
        if ( !result.getExceptions().contains( e ) )
        {
            result.addException( e );
        }

        return result;
    }

    private void validatePrerequisitesForNonMavenPluginProjects( List<MavenProject> projects )
    {
        for ( MavenProject mavenProject : projects )
        {
            if ( !"maven-plugin".equals( mavenProject.getPackaging() ) )
            {
                Prerequisites prerequisites = mavenProject.getModel().getDelegate().getPrerequisites();
                if ( prerequisites != null && prerequisites.getMaven() != null )
                {
                    logger.warn( "The project " + mavenProject.getId() + " uses prerequisites"
                        + " which is only intended for maven-plugin projects "
                        + "but not for non maven-plugin projects. "
                        + "For such purposes you should use the maven-enforcer-plugin. "
                        + "See https://maven.apache.org/enforcer/enforcer-rules/requireMavenVersion.html" );
                }
            }
        }
    }

    /**
     * Get all profiles that are detected in the projects, any parent of the projects, or the settings.
     * @param session The Maven session
     * @return A {@link Set} of profile identifiers, never {@code null}.
     */
    private Set<String> getAllProfiles( MavenSession session )
    {
        final Model superPomModel = superPomProvider.getSuperModel( "4.0.0" );
        final Set<MavenProject> projectsIncludingParents = new HashSet<>();
        for ( MavenProject project : session.getProjects() )
        {
            boolean isAdded = projectsIncludingParents.add( project );
            MavenProject parent = project.getParent();
            while ( isAdded && parent != null )
            {
                isAdded = projectsIncludingParents.add( parent );
                parent = parent.getParent();
            }
        }

        final Stream<String> projectProfiles = projectsIncludingParents.stream()
                .flatMap( p -> p.getModel().getDelegate().getProfiles().stream() )
                .map( Profile::getId );
        final Stream<String> settingsProfiles = session.getSettings().getProfiles().stream()
                .map( org.apache.maven.settings.Profile::getId );
        final Stream<String> superPomProfiles = superPomModel.getProfiles().stream()
                .map( Profile::getId );

        return Stream.of( projectProfiles, settingsProfiles, superPomProfiles )
                .flatMap( Function.identity() )
                .collect( toSet() );
    }

    /**
     * Check whether the required profiles were found in any of the projects we're building or the settings.
     * @param session the Maven session.
     * @param profileActivation the requested optional and required profiles.
     */
    private void validateRequiredProfiles( MavenSession session, ProfileActivation profileActivation )
    {
        final Set<String> allAvailableProfiles = getAllProfiles( session );

        final Set<String> requiredProfiles = new HashSet<>( );
        requiredProfiles.addAll( profileActivation.getRequiredActiveProfileIds() );
        requiredProfiles.addAll( profileActivation.getRequiredInactiveProfileIds() );

        // Check whether the required profiles were found in any of the projects we're building.
        final Set<String> notFoundRequiredProfiles = requiredProfiles.stream()
                .filter( rap -> !allAvailableProfiles.contains( rap ) )
                .collect( toSet() );

        if ( !notFoundRequiredProfiles.isEmpty() )
        {
            // Use SLF4J formatter for consistency with warnings reported by logger
            final String message = MessageFormatter.format(
                    "The requested profiles {} could not be activated or deactivated because they do not"
                            + " exist.", notFoundRequiredProfiles ).getMessage();
            addExceptionToResult( session.getResult(), new MissingProfilesException( message ) );
        }
    }

    /**
     * Check whether any of the requested optional projects were not activated or deactivated.
     * @param request the {@link MavenExecutionRequest}.
     * @param session the {@link MavenSession}.
     */
    private void validateOptionalProjects( MavenExecutionRequest request, MavenSession session )
    {
        final ProjectActivation projectActivation = request.getProjectActivation();
        final Set<String> allOptionalSelectors = new HashSet<>();
        allOptionalSelectors.addAll( projectActivation.getOptionalActiveProjectSelectors() );
        allOptionalSelectors.addAll( projectActivation.getRequiredActiveProjectSelectors() );
        // We intentionally ignore the results of this method.
        // As a side effect it will log the optional projects that could not be resolved.
        projectSelector.getOptionalProjectsBySelectors( request, session.getAllProjects(), allOptionalSelectors );
    }

    /**
     * Check whether any of the requested optional profiles were not activated or deactivated.
     * @param session the Maven session.
     * @param profileActivation the requested optional and required profiles.
     */
    private void validateOptionalProfiles( MavenSession session, ProfileActivation profileActivation )
    {
        final Set<String> allAvailableProfiles = getAllProfiles( session );

        final Set<String> optionalProfiles = new HashSet<>( );
        optionalProfiles.addAll( profileActivation.getOptionalActiveProfileIds() );
        optionalProfiles.addAll( profileActivation.getOptionalInactiveProfileIds() );

        final Set<String> notFoundOptionalProfiles = optionalProfiles.stream()
                .filter( rap -> !allAvailableProfiles.contains( rap ) )
                .collect( toSet() );

        if ( !notFoundOptionalProfiles.isEmpty() )
        {
            logger.info( "The requested optional profiles {} could not be activated or deactivated because they do not"
                            + " exist.", notFoundOptionalProfiles );
        }
    }

    private Map<String, MavenProject> getProjectMap( Collection<MavenProject> projects )
        throws DuplicateProjectException
    {
        Map<String, MavenProject> index = new LinkedHashMap<>();
        Map<String, List<File>> collisions = new LinkedHashMap<>();

        for ( MavenProject project : projects )
        {
            String projectId = ArtifactUtils.key( project.getGroupId(), project.getArtifactId(), project.getVersion() );

            MavenProject collision = index.get( projectId );

            if ( collision == null )
            {
                index.put( projectId, project );
            }
            else
            {
                List<File> pomFiles = collisions.get( projectId );

                if ( pomFiles == null )
                {
                    pomFiles = new ArrayList<>( Arrays.asList( collision.getFile(), project.getFile() ) );
                    collisions.put( projectId, pomFiles );
                }
                else
                {
                    pomFiles.add( project.getFile() );
                }
            }
        }

        if ( !collisions.isEmpty() )
        {
            throw new DuplicateProjectException( "Two or more projects in the reactor"
                + " have the same identifier, please make sure that <groupId>:<artifactId>:<version>"
                + " is unique for each project: " + collisions, collisions );
        }

        return index;
    }

    private Result<? extends ProjectDependencyGraph> buildGraph( MavenSession session )
    {
        Result<? extends ProjectDependencyGraph> graphResult = graphBuilder.build( session );
        for ( ModelProblem problem : graphResult.getProblems() )
        {
            if ( problem.getSeverity() == ModelProblem.Severity.WARNING )
            {
                logger.warn( problem.getMessage() );
            }
            else
            {
                logger.error( problem.getMessage() );
            }
        }

        if ( !graphResult.hasErrors() )
        {
            ProjectDependencyGraph projectDependencyGraph = graphResult.get();
            session.setProjects( projectDependencyGraph.getSortedProjects() );
            session.setAllProjects( projectDependencyGraph.getAllProjects() );
            session.setProjectDependencyGraph( projectDependencyGraph );
        }

        return graphResult;
    }

    @Deprecated
    // 5 January 2014
    protected Logger getLogger()
    {
        return logger;
    }
}
