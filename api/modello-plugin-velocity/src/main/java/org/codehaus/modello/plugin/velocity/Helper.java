package org.codehaus.modello.plugin.velocity;

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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.codehaus.modello.ModelloRuntimeException;
import org.codehaus.modello.model.ModelAssociation;
import org.codehaus.modello.model.ModelClass;
import org.codehaus.modello.model.ModelField;
import org.codehaus.modello.model.Version;
import org.codehaus.modello.plugin.AbstractModelloGenerator;
import org.codehaus.modello.plugins.xml.metadata.XmlAssociationMetadata;
import org.codehaus.modello.plugins.xml.metadata.XmlClassMetadata;
import org.codehaus.modello.plugins.xml.metadata.XmlFieldMetadata;
import org.codehaus.plexus.util.StringUtils;

@SuppressWarnings( "unused" )
public class Helper
{
    private final Version version;

    public Helper( Version version )
    {
        this.version = version;
    }

    public String capitalise( String str )
    {
        return StringUtils.isEmpty( str ) ? str : Character.toTitleCase( str.charAt( 0 ) ) + str.substring( 1 );
    }

    public String uncapitalise( String str )
    {
        return StringUtils.isEmpty( str ) ? str : Character.toLowerCase( str.charAt( 0 ) ) + str.substring( 1 );
    }

    public String singular( String str )
    {
        return AbstractModelloGenerator.singular( str );
    }

    public List<ModelClass> ancestors( ModelClass clazz )
    {
        List<ModelClass> ancestors = new ArrayList<>();
        for ( ModelClass cl = clazz; cl != null; cl = cl.getSuperClass() != null
                ? cl.getModel().getClass( cl.getSuperClass(), version ) : null )
        {
            ancestors.add( 0, cl );
        }
        return ancestors;
    }

    public XmlClassMetadata xmlClassMetadata( ModelClass clazz )
    {
        return (XmlClassMetadata) clazz.getMetadata( XmlClassMetadata.ID );
    }

    public XmlFieldMetadata xmlFieldMetadata( ModelField field )
    {
        return (XmlFieldMetadata) field.getMetadata( XmlFieldMetadata.ID );
    }

    public XmlAssociationMetadata xmAssociationMetadata( ModelField field )
    {
        return (XmlAssociationMetadata) ( (ModelAssociation) field )
                .getAssociationMetadata( XmlAssociationMetadata.ID );
    }

    public boolean isFlatItems( ModelField field )
    {
        return field instanceof ModelAssociation && xmAssociationMetadata( field ).isFlatItems();
    }

    public List<ModelField> xmlFields( ModelClass modelClass )
    {
        List<ModelClass> classes = new ArrayList<>();
        // get the full inheritance
        while ( modelClass != null )
        {
            classes.add( modelClass );
            String superClass = modelClass.getSuperClass();
            if ( superClass != null )
            {
                // superClass can be located outside (not generated by modello)
                modelClass = modelClass.getModel().getClass( superClass, version, true );
            }
            else
            {
                modelClass = null;
            }
        }
        List<ModelField> fields = new ArrayList<>();
        for ( int i = classes.size() - 1; i >= 0; i-- )
        {
            modelClass = classes.get( i );
            Iterator<ModelField> parentIter = fields.iterator();
            fields = new ArrayList<>();
            for ( ModelField field : modelClass.getFields( version ) )
            {
                XmlFieldMetadata xmlFieldMetadata = (XmlFieldMetadata) field.getMetadata( XmlFieldMetadata.ID );
                if ( xmlFieldMetadata.isTransient() )
                {
                    // just ignore xml.transient fields
                    continue;
                }
                if ( xmlFieldMetadata.getInsertParentFieldsUpTo() != null )
                {
                    // insert fields from parent up to the specified field
                    boolean found = false;
                    while ( !found && parentIter.hasNext() )
                    {
                        ModelField parentField = parentIter.next();
                        fields.add( parentField );
                        found = parentField.getName().equals( xmlFieldMetadata.getInsertParentFieldsUpTo() );
                    }
                    if ( !found )
                    {
                        // interParentFieldsUpTo not found
                        throw new ModelloRuntimeException( "parent field not found: class "
                                + modelClass.getName() + " xml.insertParentFieldUpTo='"
                                + xmlFieldMetadata.getInsertParentFieldsUpTo() + "'" );
                    }
                }
                fields.add( field );
            }
            // add every remaining fields from parent class
            while ( parentIter.hasNext() )
            {
                fields.add( parentIter.next() );
            }
        }
        return fields;
    }
}
