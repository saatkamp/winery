/*******************************************************************************
 * Copyright (c) 2012-2017 University of Stuttgart.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and the Apache License 2.0 which both accompany this distribution,
 * and are available at http://www.eclipse.org/legal/epl-v10.html
 * and http://www.apache.org/licenses/LICENSE-2.0
 *
 * Contributors:
 *     Oliver Kopp - initial API and implementation
 *     Tino Stadelmaier, Philipp Meyer - rename for id/namespace
 *     Karoline Saatkamp - add injector APIs
 *******************************************************************************/
package org.eclipse.winery.repository.resources.servicetemplates;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.winery.common.RepositoryFileReference;
import org.eclipse.winery.common.ids.XMLId;
import org.eclipse.winery.common.ids.definitions.ServiceTemplateId;
import org.eclipse.winery.common.ids.definitions.TOSCAComponentId;
import org.eclipse.winery.common.ids.elements.PlanId;
import org.eclipse.winery.common.ids.elements.PlansId;
import org.eclipse.winery.model.tosca.TBoundaryDefinitions;
import org.eclipse.winery.model.tosca.TEntityTemplate;
import org.eclipse.winery.model.tosca.TExtensibleElements;
import org.eclipse.winery.model.tosca.TNodeTemplate;
import org.eclipse.winery.model.tosca.TPlan;
import org.eclipse.winery.model.tosca.TPlan.PlanModelReference;
import org.eclipse.winery.model.tosca.TPlans;
import org.eclipse.winery.model.tosca.TRequirement;
import org.eclipse.winery.model.tosca.TServiceTemplate;
import org.eclipse.winery.model.tosca.TTopologyTemplate;
import org.eclipse.winery.repository.Utils;
import org.eclipse.winery.repository.backend.BackendUtils;
import org.eclipse.winery.repository.backend.Repository;
import org.eclipse.winery.repository.resources.AbstractComponentInstanceWithReferencesResource;
import org.eclipse.winery.repository.resources.AbstractComponentsResource;
import org.eclipse.winery.repository.resources.IHasName;
import org.eclipse.winery.repository.resources._support.dataadapter.InjectorReplaceData;
import org.eclipse.winery.repository.resources._support.dataadapter.InjectorReplaceOptions;
import org.eclipse.winery.repository.resources.servicetemplates.boundarydefinitions.BoundaryDefinitionsResource;
import org.eclipse.winery.repository.resources.servicetemplates.plans.PlansResource;
import org.eclipse.winery.repository.resources.servicetemplates.selfserviceportal.SelfServicePortalResource;
import org.eclipse.winery.repository.resources.servicetemplates.topologytemplates.TopologyTemplateResource;
import org.eclipse.winery.repository.splitting.Splitting;
import org.eclipse.winery.repository.splitting.SplittingException;

import org.restdoc.annotations.RestDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class ServiceTemplateResource extends AbstractComponentInstanceWithReferencesResource implements IHasName {

	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceTemplateResource.class);


	public ServiceTemplateResource(ServiceTemplateId id) {
		super(id);
	}

	/** sub-resources **/

	@Path("topologytemplate/")
	public TopologyTemplateResource getTopologyTemplateResource() {
		if (this.getServiceTemplate().getTopologyTemplate() == null) {
			// the main service template resource exists
			// default topology template: empty template
			// This eases the JSPs etc. and is valid as a non-existant topology template is equal to an empty one
			this.getServiceTemplate().setTopologyTemplate(new TTopologyTemplate());
		}
		return new TopologyTemplateResource(this);
	}

	@Path("plans/")
	public PlansResource getPlansResource() {
		TPlans plans = this.getServiceTemplate().getPlans();
		if (plans == null) {
			plans = new TPlans();
			this.getServiceTemplate().setPlans(plans);
		}
		return new PlansResource(plans.getPlan(), this);
	}

	@Path("selfserviceportal/")
	public SelfServicePortalResource getSelfServicePortalResource() {
		return new SelfServicePortalResource(this);
	}

	@Path("boundarydefinitions/")
	public BoundaryDefinitionsResource getBoundaryDefinitionsResource() {
		TBoundaryDefinitions boundaryDefinitions = this.getServiceTemplate().getBoundaryDefinitions();
		if (boundaryDefinitions == null) {
			boundaryDefinitions = new TBoundaryDefinitions();
			this.getServiceTemplate().setBoundaryDefinitions(boundaryDefinitions);
		}
		return new BoundaryDefinitionsResource(this, boundaryDefinitions);
	}

	@Override
	public String getName() {
		String name = this.getServiceTemplate().getName();
		if (name == null) {
			// place default
			name = this.getId().getXmlId().getDecoded();
		}
		return name;
	}

	@Override
	public Response setName(String name) {
		this.getServiceTemplate().setName(name);
		return BackendUtils.persist(this);
	}

	// @formatter:off
	@GET
	@RestDoc(methodDescription = "Returns the associated node type, which can be substituted by this service template.<br />" +
	"@return a QName of the form {namespace}localName is returned.")
	@Path("substitutableNodeType")
	@Produces(MediaType.TEXT_PLAIN)
	// @formatter:on
	public Response getSubstitutableNodeTypeAsResponse() {
		QName qname = this.getServiceTemplate().getSubstitutableNodeType();
		if (qname == null) {
			return Response.status(Status.NOT_FOUND).build();
		} else {
			return Response.ok(qname.toString()).build();
		}
	}

	/**
	 *
	 * @return null if there is no substitutable node type
	 */
	public QName getSubstitutableNodeType() {
		return this.getServiceTemplate().getSubstitutableNodeType();
	}

	@DELETE
	@RestDoc(methodDescription = "Removes the association to substitutable node type")
	@Path("substitutableNodeType")
	public Response deleteSubstitutableNodeType() {
		this.getServiceTemplate().setSubstitutableNodeType(null);
		BackendUtils.persist(this);
		return Response.noContent().build();
	}

	@GET
	@Path("injector/options")
	@Produces({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.APPLICATION_JSON})
	public Response getInjectorOptions() {
		Splitting splitting = new Splitting();
		TTopologyTemplate topologyTemplate = this.getServiceTemplate().getTopologyTemplate();
		Map<String, List<TNodeTemplate>> matchingOptions;
		InjectorReplaceOptions injectionReplaceOptions = new InjectorReplaceOptions();

		try {
			matchingOptions = splitting.getMatchingOptionsWithDefaultLabeling(topologyTemplate);
			injectionReplaceOptions.setTopologyTemplate(topologyTemplate);
			injectionReplaceOptions.setInjectionOptions(matchingOptions);
		} catch (SplittingException e) {
			LOGGER.error("Could not get matching options", e);
			return Response.serverError().entity("Could not get matching options").build();
		}
		return Response.ok().entity(injectionReplaceOptions).build();
	}

	@POST
	@Path("injector/replace")
	@Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.APPLICATION_JSON})
	@Produces({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.APPLICATION_JSON})
	public Response injectNodeTemplates(InjectorReplaceData injectorReplaceData) throws IOException, ParserConfigurationException, SAXException {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder documentBuilder = dbf.newDocumentBuilder();

		Splitting splitting = new Splitting();
		Collection<TNodeTemplate> injectorNodeTemplates = injectorReplaceData.injections.values();
		Map<QName, String> tempConvertedOtherAttributes = new HashMap<>();

		for (TNodeTemplate injectorNodeTemplate : injectorNodeTemplates) {

			//Convert the wrong QName created by the JSON serialization back to a right QName
			for (Map.Entry<QName, String> otherAttribute : injectorNodeTemplate.getOtherAttributes().entrySet()) {
				QName qName = QName.valueOf(otherAttribute.getKey().getLocalPart());
				tempConvertedOtherAttributes.put(qName, otherAttribute.getValue());
			}
			injectorNodeTemplate.getOtherAttributes().clear();
			injectorNodeTemplate.getOtherAttributes().putAll(tempConvertedOtherAttributes);
			tempConvertedOtherAttributes.clear();

			// Convert the String created by the JSON serialization back to a XML dom document
			TEntityTemplate.Properties properties = injectorNodeTemplate.getProperties();
			if (properties != null) {
				Object any = properties.getAny();
				if (any instanceof String) {
					Document doc = documentBuilder.parse(new InputSource(new StringReader((String) any)));
					injectorNodeTemplate.getProperties().setAny(doc.getDocumentElement());
				}
			}
		}
		TTopologyTemplate tTopologyTemplate = splitting.injectNodeTemplates(this.getServiceTemplate().getTopologyTemplate(), injectorReplaceData.injections);
		this.getServiceTemplate().setTopologyTemplate(tTopologyTemplate);
		ServiceTemplateId injectedServiceTemplateId = new ServiceTemplateId(id.getNamespace().getDecoded(), id.getXmlId().getDecoded() + "-injected", false);
		Repository.INSTANCE.forceDelete(injectedServiceTemplateId);
		Repository.INSTANCE.flagAsExisting(injectedServiceTemplateId);
		ServiceTemplateResource splitServiceTempateResource = (ServiceTemplateResource) AbstractComponentsResource.getComponentInstaceResource(injectedServiceTemplateId);

		splitServiceTempateResource.getServiceTemplate().setTopologyTemplate(tTopologyTemplate);
		LOGGER.debug("Persisting...");
		splitServiceTempateResource.persist();
		LOGGER.debug("Persisted.");
		return Response.noContent().build();
	}

	/**
	 * Used for testing
	 */
	@GET
	@Path("injector/replace")
	@Produces({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.APPLICATION_JSON})
	public Response getInjectorReplacement() {
		//Test data
		InjectorReplaceData injectorReplaceData = new InjectorReplaceData();
		TTopologyTemplate tt = new TTopologyTemplate();
		TNodeTemplate nt1 = new TNodeTemplate();
		nt1.setId("nt1");
		TNodeTemplate nt2 = new TNodeTemplate();
		nt2.setId("nt2");
		TNodeTemplate nt3 = new TNodeTemplate();
		nt3.setId("nt3");
		TNodeTemplate.Requirements r = new TNodeTemplate.Requirements();
		TRequirement rt = new TRequirement();
		rt.setName("Requ");
		r.getRequirement().add(rt);
		nt3.setRequirements(r);
		tt.getNodeTemplateOrRelationshipTemplate().add(nt1);
		//injectorReplaceData.setTopologyTemplate(tt);
		Map<String, TNodeTemplate> replaceNodes = new HashMap<>();
		replaceNodes.put("test", nt2);
		replaceNodes.put("test2", nt3);
		injectorReplaceData.setInjections(replaceNodes);

		return Response.ok().entity(injectorReplaceData).build();
	}

	public TServiceTemplate getServiceTemplate() {
		return (TServiceTemplate) this.getElement();
	}

	@Override
	protected TExtensibleElements createNewElement() {
		return new TServiceTemplate();
	}

	@Override
	public void copyIdToFields(TOSCAComponentId id) {
		this.getServiceTemplate().setId(id.getXmlId().getDecoded());
		this.getServiceTemplate().setName(id.getXmlId().getDecoded());
		this.getServiceTemplate().setTargetNamespace(id.getNamespace().getDecoded());
	}

	/**
	 * Synchronizes the known plans with the data in the XML. When there is a
	 * stored file, but no known entry in the XML, we guess "BPEL" as language
	 * and "build plan" as type.
	 */
	@Override
	public void synchronizeReferences() {
		// locally stored plans
		TPlans plans = this.getServiceTemplate().getPlans();

		// plans stored in the repository
		PlansId plansContainerId = new PlansId((ServiceTemplateId) this.getId());
		SortedSet<PlanId> nestedPlans = Repository.INSTANCE.getNestedIds(plansContainerId, PlanId.class);

		Set<PlanId> plansToAdd = new HashSet<>();
		plansToAdd.addAll(nestedPlans);

		if (nestedPlans.isEmpty()) {
			if (plans == null) {
				// data on the file system equals the data -> no plans
				return;
			} else {
				//noinspection StatementWithEmptyBody
				// we have to check for equality later
			}
		}

		if (plans == null) {
			plans = new TPlans();
			this.getServiceTemplate().setPlans(plans);
		}

		for (Iterator<TPlan> iterator = plans.getPlan().iterator(); iterator.hasNext();) {
			TPlan plan = iterator.next();
			if (plan.getPlanModel() != null) {
				// in case, a plan is directly contained in a Model element, we do not need to do anything
				continue;
			}
			PlanModelReference planModelReference;
			if ((planModelReference = plan.getPlanModelReference()) != null) {
				String ref = planModelReference.getReference();
				if ((ref == null) || ref.startsWith("../")) {
					// references to local plans start with "../"
					// special case (due to errors in the importer): empty PlanModelReference field
					if (plan.getId() == null) {
						// invalid plan entry: no id.
						// we remove the entry
						iterator.remove();
						continue;
					}
					PlanId planId = new PlanId(plansContainerId, new XMLId(plan.getId(), false));
					if (nestedPlans.contains(planId)) {
						// everything allright
						// we do NOT need to add the plan on the HDD to the XML
						plansToAdd.remove(planId);
					} else {
						// no local storage for the plan, we remove it from the XML
						iterator.remove();
					}
				}
			}
		}

		// add all plans locally stored, but not contained in the XML, as plan element to the plans of the service template.
		List<TPlan> thePlans = plans.getPlan();
		for (PlanId planId : plansToAdd) {
			SortedSet<RepositoryFileReference> files = Repository.INSTANCE.getContainedFiles(planId);
			if (files.size() != 1) {
				throw new IllegalStateException("Currently, only one file per plan is supported.");
			}
			RepositoryFileReference ref = files.iterator().next();

			TPlan plan = new TPlan();
			plan.setId(planId.getXmlId().getDecoded());
			plan.setName(planId.getXmlId().getDecoded());
			plan.setPlanType(org.eclipse.winery.repository.Constants.TOSCA_PLANTYPE_BUILD_PLAN);
			plan.setPlanLanguage(org.eclipse.winery.common.constants.Namespaces.URI_BPEL20_EXECUTABLE);

			// create a PlanModelReferenceElement pointing to that file
			String path = Utils.getURLforPathInsideRepo(BackendUtils.getPathInsideRepo(ref));
			// path is relative from the definitions element
			path = "../" + path;
			PlanModelReference pref = new PlanModelReference();
			pref.setReference(path);

			plan.setPlanModelReference(pref);
			thePlans.add(plan);
		}

		try {
			this.persist();
		} catch (IOException e) {
			throw new IllegalStateException("Could not persist resource", e);
		}
	}
}
