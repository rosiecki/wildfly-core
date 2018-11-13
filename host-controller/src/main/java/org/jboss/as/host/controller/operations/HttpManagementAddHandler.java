/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.host.controller.operations;

import static org.jboss.as.host.controller.logging.HostControllerLogger.ROOT_LOGGER;
import static org.jboss.as.host.controller.resources.HttpManagementResourceDefinition.ATTRIBUTE_DEFINITIONS;
import static org.jboss.as.remoting.RemotingHttpUpgradeService.HTTP_UPGRADE_REGISTRY;
import static org.jboss.as.server.mgmt.UndertowHttpManagementService.EXTENSIBLE_HTTP_MANAGEMENT_CAPABILITY;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;

import io.undertow.server.ListenerRegistry;
import org.jboss.as.controller.CapabilityServiceBuilder;
import org.jboss.as.controller.CapabilityServiceTarget;
import org.jboss.as.controller.ControlledProcessStateService;
import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.management.BaseHttpInterfaceAddStepHandler;
import org.jboss.as.controller.management.HttpInterfaceCommonPolicy;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.domain.http.server.ConsoleMode;
import org.jboss.as.domain.http.server.ManagementHttpRequestProcessor;
import org.jboss.as.domain.management.SecurityRealm;
import org.jboss.as.host.controller.DomainModelControllerService;
import org.jboss.as.host.controller.HostControllerEnvironment;
import org.jboss.as.host.controller.HostControllerService;
import org.jboss.as.host.controller.resources.HttpManagementResourceDefinition;
import org.jboss.as.network.NetworkInterfaceBinding;
import org.jboss.as.remoting.RemotingHttpUpgradeService;
import org.jboss.as.remoting.RemotingServices;
import org.jboss.as.remoting.management.ManagementChannelRegistryService;
import org.jboss.as.remoting.management.ManagementRemotingServices;
import org.jboss.as.server.ExternalManagementRequestExecutor;
import org.jboss.as.server.mgmt.HttpManagementRequestsService;
import org.jboss.as.server.mgmt.HttpShutdownService;
import org.jboss.as.server.mgmt.ManagementWorkerService;
import org.jboss.as.server.mgmt.UndertowHttpManagementService;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.wildfly.security.auth.server.HttpAuthenticationFactory;
import org.xnio.XnioWorker;


/**
 * A handler that activates the HTTP management API.
 *
 * @author Jason T. Greene
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class HttpManagementAddHandler extends BaseHttpInterfaceAddStepHandler {

    private static final String HTTP_AUTHENTICATION_FACTORY_CAPABILITY = "org.wildfly.security.http-authentication-factory";

    public static final String OPERATION_NAME = ModelDescriptionConstants.ADD;

    private final LocalHostControllerInfoImpl hostControllerInfo;
    private final HostControllerEnvironment environment;

    public HttpManagementAddHandler(final LocalHostControllerInfoImpl hostControllerInfo, final HostControllerEnvironment environment) {
        super(ATTRIBUTE_DEFINITIONS);
        this.hostControllerInfo = hostControllerInfo;
        this.environment = environment;
    }

    @Override
    protected void populateModel(OperationContext context, ModelNode operation, Resource resource)
            throws OperationFailedException {
        super.populateModel(context, operation, resource);
        HttpManagementResourceDefinition.addAttributeValidator(context);
    }

    @Override
    protected boolean requiresRuntime(OperationContext context) {
        return super.requiresRuntime(context)
                && (context.getProcessType() != ProcessType.EMBEDDED_HOST_CONTROLLER);
    }

    @Override
    protected List<ServiceName> installServices(OperationContext context, HttpInterfaceCommonPolicy commonPolicy, ModelNode model) throws OperationFailedException {
        populateHostControllerInfo(hostControllerInfo, context, model);

        CapabilityServiceTarget serviceTarget = context.getCapabilityServiceTarget();
        boolean onDemand = context.isBooting();
        String interfaceName = hostControllerInfo.getHttpManagementInterface();
        int port = hostControllerInfo.getHttpManagementPort();
        String secureInterfaceName = hostControllerInfo.getHttpManagementSecureInterface();
        int securePort = hostControllerInfo.getHttpManagementSecurePort();

        ROOT_LOGGER.creatingHttpManagementService(interfaceName, port, securePort);

        boolean consoleEnabled = HttpManagementResourceDefinition.CONSOLE_ENABLED.resolveModelAttribute(context, model).asBoolean();
        ConsoleMode consoleMode = ConsoleMode.CONSOLE;

        if (consoleEnabled) {
            if (context.getRunningMode() == RunningMode.ADMIN_ONLY) {
                consoleMode = ConsoleMode.ADMIN_ONLY;
            } else if (!hostControllerInfo.isMasterDomainController()) {
                consoleMode = ConsoleMode.SLAVE_HC;
            }
        } else {
            consoleMode = ConsoleMode.NO_CONSOLE;
        }

        NativeManagementServices.installManagementWorkerService(serviceTarget, context.getServiceRegistry(false));

        // Track active requests
        final ServiceName requestProcessorName = UndertowHttpManagementService.SERVICE_NAME.append("requests");
        HttpManagementRequestsService.installService(requestProcessorName, serviceTarget);

        final UndertowHttpManagementService service = new UndertowHttpManagementService(consoleMode, environment.getProductConfig().getConsoleSlot());
        service.getPortInjector().inject(port);
        service.getSecurePortInjector().inject(securePort);
        service.getAllowedOriginsInjector().inject(commonPolicy.getAllowedOrigins());
        CapabilityServiceBuilder<?> builder = serviceTarget.addCapability(EXTENSIBLE_HTTP_MANAGEMENT_CAPABILITY, service)
                .addCapabilityRequirement("org.wildfly.network.interface",
                        NetworkInterfaceBinding.class, service.getInterfaceInjector(), interfaceName)
                .addCapabilityRequirement("org.wildfly.network.interface",
                        NetworkInterfaceBinding.class, service.getSecureInterfaceInjector(), secureInterfaceName)
                .addDependency(DomainModelControllerService.SERVICE_NAME, ModelController.class, service.getModelControllerInjector())
                .addDependency(ControlledProcessStateService.SERVICE_NAME, ControlledProcessStateService.class, service.getControlledProcessStateServiceInjector())
                .addDependency(RemotingServices.HTTP_LISTENER_REGISTRY, ListenerRegistry.class, service.getListenerRegistry())
                .addDependency(requestProcessorName, ManagementHttpRequestProcessor.class, service.getRequestProcessorValue())
                .addDependency(ManagementWorkerService.SERVICE_NAME, XnioWorker.class, service.getWorker())
                .addDependency(ExternalManagementRequestExecutor.SERVICE_NAME, Executor.class, service.getManagementExecutor());

        String httpAuthenticationFactory = commonPolicy.getHttpAuthenticationFactory();
        String securityRealm = commonPolicy.getSecurityRealm();
        if (httpAuthenticationFactory != null) {
            builder.addCapabilityRequirement(HTTP_AUTHENTICATION_FACTORY_CAPABILITY, HttpAuthenticationFactory.class,
                    service.getHttpAuthenticationFactoryInjector(), httpAuthenticationFactory);
        } else if (securityRealm != null) {
            SecurityRealm.ServiceUtil.addDependency(builder, service.getSecurityRealmInjector(), securityRealm);
        } else {
            ROOT_LOGGER.httpManagementInterfaceIsUnsecured();
        }
        String sslContext = commonPolicy.getSSLContext();
        if (sslContext != null) {
            builder.addCapabilityRequirement(SSL_CONTEXT_CAPABILITY, SSLContext.class, service.getSSLContextInjector(), sslContext);
        }

        builder.setInitialMode(onDemand ? ServiceController.Mode.ON_DEMAND : ServiceController.Mode.ACTIVE)
            .install();

        // Add service preventing the server from shutting down
        final HttpShutdownService shutdownService = new HttpShutdownService();
        final ServiceName shutdownName = UndertowHttpManagementService.SERVICE_NAME.append("shutdown");
        final ServiceBuilder sb = serviceTarget.addService(shutdownName, shutdownService);
        sb.addDependency(requestProcessorName, ManagementHttpRequestProcessor.class, shutdownService.getProcessorValue());
        sb.addDependency(HostControllerService.HC_EXECUTOR_SERVICE_NAME, Executor.class, shutdownService.getExecutorValue());
        sb.addDependency(ManagementChannelRegistryService.SERVICE_NAME, ManagementChannelRegistryService.class, shutdownService.getMgmtChannelRegistry());
        sb.requires(UndertowHttpManagementService.SERVICE_NAME);
        sb.install();

        if (commonPolicy.isHttpUpgradeEnabled()) {
            NativeManagementServices.installRemotingServicesIfNotInstalled(serviceTarget, hostControllerInfo.getLocalHostName(), context.getServiceRegistry(true), onDemand);
            final String httpConnectorName;
            if (port > -1 || securePort < 0) {
                httpConnectorName = ManagementRemotingServices.HTTP_CONNECTOR;
            } else {
                httpConnectorName = ManagementRemotingServices.HTTPS_CONNECTOR;
            }

            RemotingHttpUpgradeService.installServices(context, ManagementRemotingServices.HTTP_CONNECTOR, httpConnectorName,
                    ManagementRemotingServices.MANAGEMENT_ENDPOINT, commonPolicy.getConnectorOptions(), securityRealm, commonPolicy.getSaslAuthenticationFactory());
            return Arrays.asList(UndertowHttpManagementService.SERVICE_NAME, HTTP_UPGRADE_REGISTRY.append(httpConnectorName));
        }
        return Collections.singletonList(UndertowHttpManagementService.SERVICE_NAME);
    }

    @Override
    protected void rollbackRuntime(OperationContext context, ModelNode operation, Resource resource) {
        HttpManagementRemoveHandler.clearHostControllerInfo(hostControllerInfo);
    }

    static void populateHostControllerInfo(final LocalHostControllerInfoImpl hostControllerInfo, final OperationContext context, final ModelNode model) throws OperationFailedException {
        hostControllerInfo.setHttpManagementInterface(HttpManagementResourceDefinition.INTERFACE.resolveModelAttribute(context, model).asString());
        final ModelNode portNode = HttpManagementResourceDefinition.HTTP_PORT.resolveModelAttribute(context, model);
        hostControllerInfo.setHttpManagementPort(portNode.isDefined() ? portNode.asInt() : -1);
        final ModelNode secureAddress = HttpManagementResourceDefinition.SECURE_INTERFACE.resolveModelAttribute(context, model);
        hostControllerInfo.setHttpManagementSecureInterface(secureAddress.isDefined() ? secureAddress.asString() : null);
        final ModelNode securePortNode = HttpManagementResourceDefinition.HTTPS_PORT.resolveModelAttribute(context, model);
        hostControllerInfo.setHttpManagementSecurePort(securePortNode.isDefined() ? securePortNode.asInt() : -1);
    }

}
