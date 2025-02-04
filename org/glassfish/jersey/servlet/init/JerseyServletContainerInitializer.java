/*
 * Copyright (c) 2012, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.servlet.init;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.Path;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.Provider;

import jakarta.servlet.Registration;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.HandlesTypes;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.glassfish.jersey.servlet.ServletProperties;
import org.glassfish.jersey.servlet.init.internal.LocalizationMessages;
import org.glassfish.jersey.servlet.internal.ServletContainerProviderFactory;
import org.glassfish.jersey.servlet.internal.Utils;
import org.glassfish.jersey.servlet.internal.spi.ServletContainerProvider;

/*
 It is RECOMMENDED that implementations support the Servlet 3 framework
 pluggability mechanism to enable portability between containers and to avail
 themselves of container-supplied class scanning facilities.
 When using the pluggability mechanism the following conditions MUST be met:

 - If no Application subclass is present the added servlet MUST be
 named "javax.ws.rs.core.Application" and all root resource classes and
 providers packaged in the web application MUST be included in the published
 JAX-RS application. The application MUST be packaged with a web.xml that
 specifies a servlet mapping for the added servlet.

 - If an Application subclass is present and there is already a servlet defined
 that has a servlet initialization parameter named "javax.ws.rs.Application"
 whose value is the fully qualified name of the Application subclass then no
 servlet should be added by the JAX-RS implementation's ContainerInitializer
 since the application is already being handled by an existing servlet.

 - If an application subclass is present that is not being handled by an
 existing servlet then the servlet added by the ContainerInitializer MUST be
 named with the fully qualified name of the Application subclass.  If the
 Application subclass is annotated with @ApplicationPath and no servlet-mapping
 exists for the added servlet then a new servlet mapping is added with the
 value of the @ApplicationPath annotation with "/*" appended otherwise the existing
 mapping is used. If the Application subclass is not annotated with @ApplicationPath
 then the application MUST be packaged with a web.xml that specifies a servlet
 mapping for the added servlet. It is an error for more than one Application
 to be deployed at the same effective servlet mapping.

 In either of the latter two cases, if both Application#getClasses and
 Application#getSingletons return an empty list then all root resource classes
 and providers packaged in the web application MUST be included in the
 published JAX-RS application. If either getClasses or getSingletons return a
 non-empty list then only those classes or singletons returned MUST be included
 in the published JAX-RS application.

 If not using the Servlet 3 framework pluggability mechanism
 (e.g. in a pre-Servlet 3.0 container), the servlet-class or filter-class
 element of the web.xml descriptor SHOULD name the JAX-RS
 implementation-supplied Servlet or Filter class respectively. The
 application-supplied subclass of Application SHOULD be identified using an
 init-param with a param-name of javax.ws.rs.Application.
 */

/**
 * {@link ServletContainerInitializer} implementation used for Servlet 3.x deployment.
 *
 * @author Paul Sandoz
 * @author Martin Matula
 * @author Libor Kramolis
 */
@HandlesTypes({ Path.class, Provider.class, Application.class, ApplicationPath.class })
public final class JerseyServletContainerInitializer implements ServletContainerInitializer {

    private static final Logger LOGGER = Logger.getLogger(JerseyServletContainerInitializer.class.getName());

    @Override
    public void onStartup(Set<Class<?>> classes, final ServletContext servletContext) throws ServletException {
        final ServletContainerProvider[] allServletContainerProviders =
                ServletContainerProviderFactory.getAllServletContainerProviders();

        if (classes == null) {
            classes = Collections.emptySet();
        }
        // PRE INIT
        for (final ServletContainerProvider servletContainerProvider : allServletContainerProviders) {
            servletContainerProvider.preInit(servletContext, classes);
        }
        // INIT IMPL
        onStartupImpl(classes, servletContext);
        // POST INIT
        for (final ServletContainerProvider servletContainerProvider : allServletContainerProviders) {
            servletContainerProvider.postInit(servletContext, classes, findJerseyServletNames(servletContext));
        }
        // ON REGISTER
        for (final ServletContainerProvider servletContainerProvider : allServletContainerProviders) {
            servletContainerProvider.onRegister(servletContext, findJerseyServletNames(servletContext));
        }
    }

    private void onStartupImpl(final Set<Class<?>> classes, final ServletContext servletContext) throws ServletException {
        final Set<ServletRegistration> registrationsWithApplication = new HashSet<>();

        // first see if there are any application classes in the web app
        for (final Class<? extends Application> applicationClass : getApplicationClasses(classes)) {
            final ServletRegistration servletRegistration = servletContext.getServletRegistration(applicationClass.getName());

            if (servletRegistration != null) {
                addServletWithExistingRegistration(servletContext, servletRegistration, applicationClass, classes);
                registrationsWithApplication.add(servletRegistration);
            } else {
                // Servlet is not registered with app name or the app name is used to register a different servlet
                // check if some servlet defines the app in init params
                final List<Registration> srs = getInitParamDeclaredRegistrations(servletContext, applicationClass);
                if (!srs.isEmpty()) {
                    // app handled by at least one servlet or filter
                    // fix the registrations if needed (i.e. add servlet class)
                    for (final Registration sr : srs) {
                        if (sr instanceof ServletRegistration) {
                            addServletWithExistingRegistration(servletContext, (ServletRegistration) sr,
                                    applicationClass, classes);
                            registrationsWithApplication.add((ServletRegistration) sr);
                        }
                    }
                } else {
                    // app not handled by any servlet/filter -> add it
                    final ServletRegistration sr = addServletWithApplication(servletContext, applicationClass, classes);
                    if (sr != null) {
                        registrationsWithApplication.add(sr);
                    }
                }
            }
        }

        // check for javax.ws.rs.core.Application registration
        addServletWithDefaultConfiguration(servletContext, registrationsWithApplication, classes);
    }

    /**
     * Returns names of all registered Jersey servlets.
     *
     * Servlets are configured in {@code web.xml} or managed via Servlet API.
     *
     * @param servletContext the {@link ServletContext} of the web application that is being started
     * @return list of Jersey servlet names or empty array, never returns {@code null}
     */
    private static Set<String> findJerseyServletNames(final ServletContext servletContext) {
        final Set<String> jerseyServletNames = new HashSet<>();

        for (final ServletRegistration servletRegistration : servletContext.getServletRegistrations().values()) {
            if (isJerseyServlet(servletRegistration.getClassName())) {
                jerseyServletNames.add(servletRegistration.getName());
            }
        }
        return Collections.unmodifiableSet(jerseyServletNames);
    }

    /**
     * Check if the {@code className} is an implementation of a Jersey Servlet container.
     *
     * @return {@code true} if the class is a Jersey servlet container class, {@code false} otherwise.
     */
    private static boolean isJerseyServlet(final String className) {
        return ServletContainer.class.getName().equals(className)
                || "org.glassfish.jersey.servlet.portability.PortableServletContainer".equals(className);
    }

    private static List<Registration> getInitParamDeclaredRegistrations(final ServletContext context,
                                                                        final Class<? extends Application> clazz) {
        final List<Registration> registrations = new ArrayList<>();
        collectJaxRsRegistrations(context.getServletRegistrations(), registrations, clazz);
        collectJaxRsRegistrations(context.getFilterRegistrations(), registrations, clazz);
        return registrations;
    }

    private static void collectJaxRsRegistrations(final Map<String, ? extends Registration> registrations,
                                                  final List<Registration> collected, final Class<? extends Application> a) {
        for (final Registration sr : registrations.values()) {
            final Map<String, String> ips = sr.getInitParameters();
            if (ips.containsKey(ServletProperties.JAXRS_APPLICATION_CLASS)) {
                if (ips.get(ServletProperties.JAXRS_APPLICATION_CLASS).equals(a.getName())) {
                    collected.add(sr);
                }
            }
        }
    }

    /**
     * Enhance default servlet (named {@link Application}) configuration.
     */
    private static void addServletWithDefaultConfiguration(final ServletContext context,
                                                           final Set<ServletRegistration> registrationsWithApplication,
                                                           final Set<Class<?>> classes) throws ServletException {

        ServletRegistration registration = context.getServletRegistration(Application.class.getName());

        final Set<Class<?>> appClasses = getRootResourceAndProviderClasses(classes);

        if (registration != null) {
            final ResourceConfig resourceConfig = ResourceConfig.forApplicationClass(ResourceConfig.class, appClasses)
                    .addProperties(getInitParams(registration))
                    .addProperties(Utils.getContextParams(context));

            if (registration.getClassName() != null) {
                // class name present - complete servlet registration from container point of view
                Utils.store(resourceConfig, context, registration.getName());
            } else {
                // no class name - no complete servlet registration from container point of view
                final ServletContainer servlet = new ServletContainer(resourceConfig);
                registration = context.addServlet(registration.getName(), servlet);
                ((ServletRegistration.Dynamic) registration).setLoadOnStartup(1);

                if (registration.getMappings().isEmpty()) {
                    // Error
                    LOGGER.log(Level.WARNING, LocalizationMessages.JERSEY_APP_NO_MAPPING(registration.getName()));
                } else {
                    LOGGER.log(Level.CONFIG,
                            LocalizationMessages.JERSEY_APP_REGISTERED_CLASSES(registration.getName(), appClasses));
                }
            }
        }

        // make <servlet-class>org.glassfish.jersey.servlet.ServletContainer</servlet-class> without init params
        // work the same as <servlet-name>javax.ws.rs.Application</servlet-name>
        for (ServletRegistration servletRegistration : context.getServletRegistrations().values()) {
            if (isJerseyServlet(servletRegistration.getClassName())
                    && servletRegistration != registration
                    && !registrationsWithApplication.contains(servletRegistration)
                    && getInitParams(servletRegistration).isEmpty()) {
                final ResourceConfig resourceConfig = ResourceConfig.forApplicationClass(ResourceConfig.class, appClasses)
                        .addProperties(Utils.getContextParams(context));
                Utils.store(resourceConfig, context, servletRegistration.getName());
            }
        }
    }

    /**
     * Add new servlet according to {@link Application} subclass with {@link ApplicationPath} annotation or existing
     * {@code servlet-mapping}.
     */
    private static ServletRegistration addServletWithApplication(final ServletContext context,
                                                  final Class<? extends Application> clazz,
                                                  final Set<Class<?>> defaultClasses) throws ServletException {
        final ApplicationPath ap = clazz.getAnnotation(ApplicationPath.class);
        if (ap != null) {
            // App is annotated with ApplicationPath
            final ResourceConfig resourceConfig = ResourceConfig.forApplicationClass(clazz, defaultClasses)
                    .addProperties(Utils.getContextParams(context));
            final ServletContainer s = new ServletContainer(resourceConfig);
            final ServletRegistration.Dynamic dsr = context.addServlet(clazz.getName(), s);
            dsr.setAsyncSupported(true);
            dsr.setLoadOnStartup(1);

            final String mapping = createMappingPath(ap);
            if (!mappingExists(context, mapping)) {
                dsr.addMapping(mapping);

                LOGGER.log(Level.CONFIG, LocalizationMessages.JERSEY_APP_REGISTERED_MAPPING(clazz.getName(), mapping));
            } else {
                LOGGER.log(Level.WARNING, LocalizationMessages.JERSEY_APP_MAPPING_CONFLICT(clazz.getName(), mapping));
            }
            return dsr;
        }
        return null;
    }

    /**
     * Enhance existing servlet configuration.
     */
    private static void addServletWithExistingRegistration(final ServletContext context,
                                                           ServletRegistration registration,
                                                           final Class<? extends Application> clazz,
                                                           final Set<Class<?>> classes) throws ServletException {
        // create a new servlet container for a given app.
        final ResourceConfig resourceConfig = ResourceConfig.forApplicationClass(clazz, classes)
                .addProperties(getInitParams(registration))
                .addProperties(Utils.getContextParams(context));

        if (registration.getClassName() != null) {
            // class name present - complete servlet registration from container point of view
            Utils.store(resourceConfig, context, registration.getName());
        } else {
            // no class name - no complete servlet registration from container point of view
            final ServletContainer servlet = new ServletContainer(resourceConfig);
            final ServletRegistration.Dynamic dynamicRegistration = context.addServlet(clazz.getName(), servlet);
            dynamicRegistration.setAsyncSupported(true);
            dynamicRegistration.setLoadOnStartup(1);
            registration = dynamicRegistration;
        }
        if (registration.getMappings().isEmpty()) {
            final ApplicationPath ap = clazz.getAnnotation(ApplicationPath.class);
            if (ap != null) {
                final String mapping = createMappingPath(ap);
                if (!mappingExists(context, mapping)) {
                    registration.addMapping(mapping);

                    LOGGER.log(Level.CONFIG, LocalizationMessages.JERSEY_APP_REGISTERED_MAPPING(clazz.getName(), mapping));
                } else {
                    LOGGER.log(Level.WARNING, LocalizationMessages.JERSEY_APP_MAPPING_CONFLICT(clazz.getName(), mapping));
                }
            } else {
                // Error
                LOGGER.log(Level.WARNING, LocalizationMessages.JERSEY_APP_NO_MAPPING_OR_ANNOTATION(clazz.getName(),
                        ApplicationPath.class.getSimpleName()));
            }
        } else {
            LOGGER.log(Level.CONFIG, LocalizationMessages.JERSEY_APP_REGISTERED_APPLICATION(clazz.getName()));
        }
    }

    private static Map<String, Object> getInitParams(final ServletRegistration sr) {
        final Map<String, Object> initParams = new HashMap<>();
        for (final Map.Entry<String, String> entry : sr.getInitParameters().entrySet()) {
            initParams.put(entry.getKey(), entry.getValue());
        }
        return initParams;
    }

    private static boolean mappingExists(final ServletContext sc, final String mapping) {
        for (final ServletRegistration sr : sc.getServletRegistrations().values()) {
            for (final String declaredMapping : sr.getMappings()) {
                if (mapping.equals(declaredMapping)) {
                    return true;
                }
            }
        }

        return false;
    }



    private static String createMappingPath(final ApplicationPath ap) {
        String path = ap.value();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (!path.endsWith("/*")) {
            if (path.endsWith("/")) {
                path += "*";
            } else {
                path += "/*";
            }
        }

        return path;
    }

    private static Set<Class<? extends Application>> getApplicationClasses(final Set<Class<?>> classes) {
        final Set<Class<? extends Application>> s = new LinkedHashSet<>();
        for (final Class<?> c : classes) {
            if (Application.class != c && Application.class.isAssignableFrom(c)) {
                s.add(c.asSubclass(Application.class));
            }
        }

        return s;
    }

    private static Set<Class<?>> getRootResourceAndProviderClasses(final Set<Class<?>> classes) {
        // TODO filter out any classes from the Jersey jars
        final Set<Class<?>> s = new LinkedHashSet<>();
        for (final Class<?> c : classes) {
            if (c.isAnnotationPresent(Path.class) || c.isAnnotationPresent(Provider.class)) {
                s.add(c);
            }
        }

        return s;
    }

}
