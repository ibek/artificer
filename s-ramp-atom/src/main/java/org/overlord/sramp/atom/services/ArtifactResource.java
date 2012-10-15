/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.overlord.sramp.atom.services;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
import org.jboss.resteasy.plugins.providers.atom.Entry;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartConstants;
import org.jboss.resteasy.plugins.providers.multipart.MultipartRelatedInput;
import org.jboss.resteasy.util.GenericType;
import org.overlord.sramp.ArtifactType;
import org.overlord.sramp.ArtifactTypeEnum;
import org.overlord.sramp.MimeTypes;
import org.overlord.sramp.atom.MediaType;
import org.overlord.sramp.atom.SrampAtomUtils;
import org.overlord.sramp.atom.err.SrampAtomException;
import org.overlord.sramp.atom.visitors.ArtifactContentTypeVisitor;
import org.overlord.sramp.atom.visitors.ArtifactToFullAtomEntryVisitor;
import org.overlord.sramp.repository.DerivedArtifactsFactory;
import org.overlord.sramp.repository.PersistenceFactory;
import org.overlord.sramp.repository.PersistenceManager;
import org.overlord.sramp.visitors.ArtifactVisitorHelper;
import org.s_ramp.xmlns._2010.s_ramp.BaseArtifactType;
import org.s_ramp.xmlns._2010.s_ramp.DerivedArtifactType;

/**
 * The JAX-RS resource that handles artifact specific tasks, including:
 *
 * <ul>
 *   <li>Add an artifact (upload)</li>
 *   <li>Get an artifact (full Atom {@link Entry})</li>
 *   <li>Get artifact content (binary content)</li>
 *   <li>Update artifact meta data</li>
 *   <li>Update artifact content</li>
 *   <li>Delete an artifact</li>
 * </ul>
 *
 * @author eric.wittmann@redhat.com
 */
@Path("/s-ramp")
public class ArtifactResource {

	/**
	 * Constructor.
	 */
	public ArtifactResource() {
	}

    /**
     * S-RAMP atom POST to upload an artifact to the repository.  The artifact content should
     * be POSTed raw.
     * @param fileName
     * @param model
     * @param type
     * @param content
     * @throws SrampAtomException
     */
    @POST
    @Path("{model}/{type}")
    @Produces(MediaType.APPLICATION_ATOM_XML_ENTRY)
	public Entry create(@HeaderParam("Content-Type") String contentType,
			@HeaderParam("Slug") String fileName, @PathParam("model") String model,
			@PathParam("type") String type, InputStream is) throws SrampAtomException {
        try {
            ArtifactType artifactType = ArtifactType.valueOf(model, type);
        	if (artifactType.getArtifactType().isDerived()) {
				throw new Exception("Failed to create artifact because '" + artifactType.getArtifactType()
						+ "' is a derived type.");
        	}
        	String mimeType = MimeTypes.determineMimeType(contentType, fileName, artifactType);
        	artifactType.setMimeType(mimeType);

        	// Pick a reasonable file name if Slug is not present
        	if (fileName == null) {
        		if (artifactType.getArtifactType() == ArtifactTypeEnum.Document) {
            		fileName = "newartifact.bin";
        		} else if (artifactType.getArtifactType() == ArtifactTypeEnum.XmlDocument) {
        			fileName = "newartifact.xml";
        		} else {
            		fileName = "newartifact." + artifactType.getArtifactType().getModel();
        		}
        	}

            PersistenceManager persistenceManager = PersistenceFactory.newInstance();
            //store the content
            BaseArtifactType baseArtifactType = ArtifactType.getArtifactInstance(artifactType);  
            baseArtifactType.setName(fileName);
            BaseArtifactType artifact = persistenceManager.persistArtifact(baseArtifactType, is);

            //create the derivedArtifacts
            Collection<DerivedArtifactType> derivedArtifacts = DerivedArtifactsFactory.newInstance().deriveArtifacts(artifact);

            //persist the derivedArtifacts
            persistenceManager.persistDerivedArtifacts(artifact, derivedArtifacts);

            //return the entry containing the s-ramp artifact
            ArtifactToFullAtomEntryVisitor visitor = new ArtifactToFullAtomEntryVisitor();
            ArtifactVisitorHelper.visitArtifact(visitor, artifact);
            return visitor.getAtomEntry();
        } catch (Exception e) {
			throw new SrampAtomException(e);
        } finally {
        	IOUtils.closeQuietly(is);
        }
    }
    
    @POST
    @Path("{model}/{type}")
    @Consumes(MultipartConstants.MULTIPART_RELATED)
    @Produces(MediaType.APPLICATION_ATOM_XML_ENTRY)
    public Entry createMultiPart(@HeaderParam("Content-Type") String contentType,
            @PathParam("model") String model, @PathParam("type") String type, 
            MultipartRelatedInput input) {
        Entry atomEntry = new Entry();
        try {
            ArtifactType artifactType = ArtifactType.valueOf(model, type);
            if (artifactType.getArtifactType().isDerived()) {
                throw new Exception("Failed to create artifact because '" + artifactType.getArtifactType()
                        + "' is a derived type.");
            }

            List<InputPart> list = input.getParts();
            // Expecting 2 parts
            if (list.size()!=2) ; //throw error
            InputPart firstPart  = list.get(0);
            InputPart secondpart = list.get(1);
            
            // Getting the S-RAMP Artifact
            atomEntry = firstPart.getBody(new GenericType<Entry>() { });
            BaseArtifactType baseArtifactType = SrampAtomUtils.unwrapSrampArtifact(artifactType, atomEntry);
            String fileName = null;
            if (baseArtifactType.getName()!=null) fileName = baseArtifactType.getName();
            String mimeType = MimeTypes.determineMimeType(contentType, fileName, artifactType);
            artifactType.setMimeType(mimeType);
            
            // Processing the content itself first
            InputStream is = secondpart.getBody(new GenericType<InputStream>() { });
            PersistenceManager persistenceManager = PersistenceFactory.newInstance();
            //store the content
            BaseArtifactType intermediate = persistenceManager.persistArtifact(baseArtifactType, is);

            //create the derivedArtifacts
            Collection<DerivedArtifactType> dartifacts = DerivedArtifactsFactory.newInstance().deriveArtifacts(intermediate);

            // Persist the derivedArtifacts
            persistenceManager.persistDerivedArtifacts(intermediate, dartifacts);
            
            //TODO we could do it all at once in the persistence layer if we can reuse the updateVisitor there
            intermediate.setDescription(baseArtifactType.getDescription());
            intermediate.setCreatedBy(baseArtifactType.getCreatedBy());
            persistenceManager.updateArtifact(baseArtifactType, artifactType);
            
            ArtifactToFullAtomEntryVisitor visitor = new ArtifactToFullAtomEntryVisitor();
            ArtifactVisitorHelper.visitArtifact(visitor, intermediate);
            
            atomEntry  = visitor.getAtomEntry();
            
        } catch (Exception e) {
            //TODO
            e.printStackTrace();
        }
        
        return atomEntry;
    }

    /**
     * Called to update the meta data for an artifact.  Note that this does *not* update
     * the content of the artifact, just the meta data.
     * @param model
     * @param type
     * @param uuid
     * @param atomEntry
     * @throws SrampAtomException
     */
    @PUT
    @Path("{model}/{type}/{uuid}")
    @Consumes(MediaType.APPLICATION_ATOM_XML_ENTRY)
    public void updateMetaData(@PathParam("model") String model, @PathParam("type") String type,
    		@PathParam("uuid") String uuid, Entry atomEntry) throws SrampAtomException {
        try {
            ArtifactType artifactType = ArtifactType.valueOf(model, type);
        	BaseArtifactType artifact = SrampAtomUtils.unwrapSrampArtifact(artifactType, atomEntry);
			PersistenceManager persistenceManager = PersistenceFactory.newInstance();
			persistenceManager.updateArtifact(artifact, artifactType);
		} catch (Throwable e) {
			throw new SrampAtomException(e);
		}
    }

    /**
     * S-RAMP atom PUT to upload a new version of the artifact into the repository.
     * 
     * @param model
     * @param type
     * @param uuid
     * @param content
     * @throws SrampAtomException
     */
    @PUT
    @Path("{model}/{type}/{uuid}/media")
	public void updateContent(@HeaderParam("Content-Type") String contentType,
			@HeaderParam("Slug") String fileName, @PathParam("model") String model,
			@PathParam("type") String type, @PathParam("uuid") String uuid, InputStream content)
			throws SrampAtomException {
        ArtifactType artifactType = ArtifactType.valueOf(model, type);
    	if (artifactType.getArtifactType().isDerived()) {
			throw new SrampAtomException("Failed to create artifact because '" + artifactType.getArtifactType()
					+ "' is a derived type.");
    	}
    	String mimeType = MimeTypes.determineMimeType(contentType, fileName, artifactType);
    	artifactType.setMimeType(mimeType);
    	//TODO we need to update the S-RAMP metadata too (new updateDate, size, etc)?
        InputStream is = content;
        try {
            PersistenceManager persistenceManager = PersistenceFactory.newInstance();
            //store the content
            persistenceManager.updateArtifactContent(uuid, artifactType, is);
        } catch (Exception e) {
			throw new SrampAtomException(e);
        } finally {
        	IOUtils.closeQuietly(is);
        }
    }

    /**
     * Called to get the meta data for an s-ramp artifact.  This will return an Atom {@link Entry}
     * with the full information about the artifact.
     * @param model
     * @param type
     * @param uuid
     * @throws SrampAtomException
     */
    @GET
    @Path("{model}/{type}/{uuid}")
    @Produces(MediaType.APPLICATION_ATOM_XML_ENTRY)
	public Entry getMetaData(@PathParam("model") String model, @PathParam("type") String type,
			@PathParam("uuid") String uuid) throws SrampAtomException {
        try {
            ArtifactType artifactType = ArtifactType.valueOf(model, type);
			PersistenceManager persistenceManager = PersistenceFactory.newInstance();

			// Get the artifact by UUID
			BaseArtifactType artifact = persistenceManager.getArtifact(uuid, artifactType);
			if (artifact == null)
				throw new Exception("Artifact not found.");

			// Return the entry containing the s-ramp artifact
			ArtifactToFullAtomEntryVisitor visitor = new ArtifactToFullAtomEntryVisitor();
			ArtifactVisitorHelper.visitArtifact(visitor, artifact);
			return visitor.getAtomEntry();
		} catch (Throwable e) {
			throw new SrampAtomException(e);
		}
    }

    /**
     * Returns the content of an artifact in the s-ramp repository.
     * @param model
     * @param type
     * @param uuid
     * @throws SrampAtomException
     */
    @GET
    @Path("{model}/{type}/{uuid}/media")
	public Response getContent(@PathParam("model") String model, @PathParam("type") String type,
			@PathParam("uuid") String uuid) throws SrampAtomException {
        try {
            ArtifactType artifactType = ArtifactType.valueOf(model, type);
			PersistenceManager persistenceManager = PersistenceFactory.newInstance();
			BaseArtifactType artifact = persistenceManager.getArtifact(uuid, artifactType);
            ArtifactContentTypeVisitor ctVizzy = new ArtifactContentTypeVisitor();
            ArtifactVisitorHelper.visitArtifact(ctVizzy, artifact);
            javax.ws.rs.core.MediaType mediaType = ctVizzy.getContentType();
            artifactType.setMimeType(mediaType.toString());
			final InputStream artifactContent = persistenceManager.getArtifactContent(uuid, artifactType);
			Object output = new StreamingOutput() {
				@Override
				public void write(OutputStream output) throws IOException, WebApplicationException {
					try {
						IOUtils.copy(artifactContent, output);
					} finally {
						IOUtils.closeQuietly(artifactContent);
					}
				}
			};
	    	return Response.ok(output, artifactType.getMimeType()).build();
		} catch (Throwable e) {
			throw new SrampAtomException(e);
		}
    }

    /**
     * Called to delete an s-ramp artifact from the repository.
     * @param model
     * @param type
     * @param uuid
     * @throws SrampAtomException
     */
    @DELETE
    @Path("{model}/{type}/{uuid}")
	public void delete(@PathParam("model") String model, @PathParam("type") String type,
			@PathParam("uuid") String uuid) throws SrampAtomException {
        try {
            ArtifactType artifactType = ArtifactType.valueOf(model, type);
			PersistenceManager persistenceManager = PersistenceFactory.newInstance();

			// Delete the artifact by UUID
			persistenceManager.deleteArtifact(uuid, artifactType);
		} catch (Throwable e) {
			throw new SrampAtomException(e);
		}
    }

}