/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Stack;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Realm;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.Wrapper;
import org.apache.catalina.authenticator.NonLoginAuthenticator;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.ContainerBase;
import org.apache.catalina.core.NamingContextListener;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.core.StandardWrapper;
import org.apache.catalina.realm.GenericPrincipal;
import org.apache.catalina.realm.RealmBase;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.IOTools;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.UriUtil;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.res.StringManager;

// TODO: lazy init for the temp dir - only when a JSP is compiled or
// get temp dir is called we need to create it. This will avoid the
// need for the baseDir

// TODO: allow contexts without a base dir - i.e.
// only programmatic. This would disable the default servlet.

/**
 * Minimal tomcat starter for embedding/unit tests.
 *
 * <p>
 * Tomcat supports multiple styles of configuration and
 * startup - the most common and stable is server.xml-based,
 * implemented in org.apache.catalina.startup.Bootstrap.
 *
 * <p>
 * This class is for use in apps that embed tomcat.
 *
 * <p>
 * Requirements:
 * <ul>
 *   <li>all tomcat classes and possibly servlets are in the classpath.
 *       (for example all is in one big jar, or in eclipse CP, or in
 *        any other combination)</li>
 *
 *   <li>we need one temporary directory for work files</li>
 *
 *   <li>no config file is required. This class provides methods to
 *       use if you have a webapp with a web.xml file, but it is
 *       optional - you can use your own servlets.</li>
 * </ul>
 *
 * <p>
 * There are a variety of 'add' methods to configure servlets and webapps. These
 * methods, by default, create a simple in-memory security realm and apply it.
 * If you need more complex security processing, you can define a subclass of
 * this class.
 *
 * <p>
 * This class provides a set of convenience methods for configuring web
 * application contexts; all overloads of the method <code>addWebapp()</code>.
 * These methods are equivalent to adding a web application to the Host's
 * appBase (normally the webapps directory). These methods create a Context,
 * configure it with the equivalent of the defaults provided by
 * <code>conf/web.xml</code> (see {@link #initWebappDefaults(String)} for
 * details) and add the Context to a Host. These methods do not use a global
 * default web.xml; rather, they add a {@link LifecycleListener} to configure
 * the defaults. Any WEB-INF/web.xml and META-INF/context.xml packaged with the
 * application will be processed normally. Normal web fragment and
 * {@link javax.servlet.ServletContainerInitializer} processing will be applied.
 *
 * <p>
 * In complex cases, you may prefer to use the ordinary Tomcat API to create
 * webapp contexts; for example, you might need to install a custom Loader
 * before the call to {@link Host#addChild(Container)}. To replicate the basic
 * behavior of the <code>addWebapp</code> methods, you may want to call two
 * methods of this class: {@link #noDefaultWebXmlPath()} and
 * {@link #getDefaultWebXmlListener()}.
 *
 * <p>
 * {@link #getDefaultWebXmlListener()} returns a {@link LifecycleListener} that
 * adds the standard DefaultServlet, JSP processing, and welcome files. If you
 * add this listener, you must prevent Tomcat from applying any standard global
 * web.xml with ...
 *
 * <p>
 * {@link #noDefaultWebXmlPath()} returns a dummy pathname to configure to
 * prevent {@link ContextConfig} from trying to apply a global web.xml file.
 *
 * <p>
 * This class provides a main() and few simple CLI arguments,
 * see setters for doc. It can be used for simple tests and
 * demo.
 *
 * @see <a href="https://gitbox.apache.org/repos/asf?p=tomcat.git;a=blob;f=test/org/apache/catalina/startup/TestTomcat.java">TestTomcat</a>
 * @author Costin Manolache
 */
public class Tomcat {

    private static final StringManager sm = StringManager.getManager(Tomcat.class);

    // Some logging implementations use weak references for loggers so there is
    // the possibility that logging configuration could be lost if GC runs just
    // after Loggers are configured but before they are used. The purpose of
    // this Map is to retain strong references to explicitly configured loggers
    // so that configuration is not lost.
    private final Map<String, Logger> pinnedLoggers = new HashMap<>();

    protected Server server;

    protected int port = 8080;
    protected String hostname = "localhost";
    protected String basedir;
    protected boolean defaultConnectorCreated = false;

    private final Map<String, String> userPass = new HashMap<>();
    private final Map<String, List<String>> userRoles = new HashMap<>();
    private final Map<String, Principal> userPrincipals = new HashMap<>();

    private boolean addDefaultWebXmlToWebapp = true;

    public Tomcat() {
        ExceptionUtils.preload();
    }

    /**
     * Tomcat needs a directory for temp files. This should be the
     * first method called.
     *
     * <p>
     * By default, if this method is not called, we use:
     * <ul>
     *  <li>system properties - catalina.base, catalina.home</li>
     *  <li>[user.dir]/tomcat.$PORT</li>
     * </ul>
     * (/tmp doesn't seem a good choice for security).
     *
     * <p>
     * TODO: disable work dir if not needed ( no jsp, etc ).
     *
     * @param basedir The Tomcat base folder on which all others
     *  will be derived
     */
    public void setBaseDir(String basedir) {
        this.basedir = basedir;
    }

    /**
     * Set the port for the default connector. Must
     * be called before start().
     * @param port The port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * The the hostname of the default host, default is
     * 'localhost'.
     * @param s The default host name
     */
    public void setHostname(String s) {
        hostname = s;
    }


    /**
     * This is equivalent to adding a web application to a Host's appBase
     * (usually Tomcat's webapps directory). By default, the equivalent of the
     * default web.xml will be applied to the web application (see
     * {@link #initWebappDefaults(String)}). This may be prevented by calling
     * {@link #setAddDefaultWebXmlToWebapp(boolean)} with {@code false}. Any
     * <code>WEB-INF/web.xml</code> and <code>META-INF/context.xml</code>
     * packaged with the application will always be processed and normal web
     * fragment and {@link javax.servlet.ServletContainerInitializer} processing
     * will always be applied.
     *
     * @param contextPath The context mapping to use, "" for root context.
     * @param docBase     Base directory for the context, for static files. Must
     *                        exist, relative to the server home
     *
     * @return the deployed context
     */
    public Context addWebapp(String contextPath, String docBase) {
        return addWebapp(getHost(), contextPath, docBase);
    }


    /**
     * Copy the specified WAR file to the Host's appBase and then call
     * {@link #addWebapp(String, String)} with the newly copied WAR. The WAR
     * will <b>NOT</b> be removed from the Host's appBase when the Tomcat
     * instance stops. Note that {@link ExpandWar} provides utility methods that
     * may be used to delete the WAR and/or expanded directory if required.
     *
     * @param contextPath   The context mapping to use, "" for root context.
     * @param source        The location from which the WAR should be copied
     *
     * @return The deployed Context
     *
     * @throws IOException If an I/O error occurs while copying the WAR file
     *                     from the specified URL to the appBase
     */
    public Context addWebapp(String contextPath, URL source) throws IOException {

        ContextName cn = new ContextName(contextPath, null);

        // Make sure a conflicting web application has not already been deployed
        Host h = getHost();
        if (h.findChild(cn.getName()) != null) {
            throw new IllegalArgumentException(sm.getString("tomcat.addWebapp.conflictChild",
                    source, contextPath, cn.getName()));
        }

        // Make sure appBase does not contain a conflicting web application
        File targetWar = new File(h.getAppBaseFile(), cn.getBaseName() + ".war");
        File targetDir = new File(h.getAppBaseFile(), cn.getBaseName());

        if (targetWar.exists()) {
            throw new IllegalArgumentException(sm.getString("tomcat.addWebapp.conflictFile",
                    source, contextPath, targetWar.getAbsolutePath()));
        }
        if (targetDir.exists()) {
            throw new IllegalArgumentException(sm.getString("tomcat.addWebapp.conflictFile",
                    source, contextPath, targetDir.getAbsolutePath()));
        }

        // Should be good to copy the WAR now
        URLConnection uConn = source.openConnection();

        try (InputStream is = uConn.getInputStream();
                OutputStream os = new FileOutputStream(targetWar)) {
            IOTools.flow(is, os);
        }

        return addWebapp(contextPath, targetWar.getAbsolutePath());
    }


    /**
     * Add a context - programmatic mode, no default web.xml used. This means
     * that there is no JSP support (no JSP servlet), no default servlet and
     * no web socket support unless explicitly enabled via the programmatic
     * interface. There is also no
     * {@link javax.servlet.ServletContainerInitializer} processing and no
     * annotation processing. If a
     * {@link javax.servlet.ServletContainerInitializer} is added
     * programmatically, there will still be no scanning for
     * {@link javax.servlet.annotation.HandlesTypes} matches.
     *
     * <p>
     * API calls equivalent with web.xml:
     *
     * <pre>{@code
     *  // context-param
     *  ctx.addParameter("name", "value");
     *
     *
     *  // error-page
     *  ErrorPage ep = new ErrorPage();
     *  ep.setErrorCode(500);
     *  ep.setLocation("/error.html");
     *  ctx.addErrorPage(ep);
     *
     *  ctx.addMimeMapping("ext", "type");
     * }</pre>
     *
     *
     * <p>
     * Note: If you reload the Context, all your configuration will be lost. If
     * you need reload support, consider using a LifecycleListener to provide
     * your configuration.
     *
     * <p>
     * TODO: add the rest
     *
     * @param contextPath The context mapping to use, "" for root context.
     * @param docBase Base directory for the context, for static files.
     *  Must exist, relative to the server home
     * @return the deployed context
     */
    public Context addContext(String contextPath, String docBase) {
        return addContext(getHost(), contextPath, docBase);
    }

    /**
     * Equivalent to &lt;servlet&gt;&lt;servlet-name&gt;&lt;servlet-class&gt;.
     *
     * <p>
     * In general it is better/faster to use the method that takes a
     * Servlet as param - this one can be used if the servlet is not
     * commonly used, and want to avoid loading all deps.
     * ( for example: jsp servlet )
     *
     * You can customize the returned servlet, ex:
     *  <pre>
     *    wrapper.addInitParameter("name", "value");
     *  </pre>
     *
     * @param contextPath   Context to add Servlet to
     * @param servletName   Servlet name (used in mappings)
     * @param servletClass  The class to be used for the Servlet
     * @return The wrapper for the servlet
     */
    public Wrapper addServlet(String contextPath,
            String servletName,
            String servletClass) {
        Container ctx = getHost().findChild(contextPath);
        return addServlet((Context) ctx, servletName, servletClass);
    }

    /**
     * Static version of {@link #addServlet(String, String, String)}
     * @param ctx           Context to add Servlet to
     * @param servletName   Servlet name (used in mappings)
     * @param servletClass  The class to be used for the Servlet
     * @return The wrapper for the servlet
     */
    public static Wrapper addServlet(Context ctx,
                                      String servletName,
                                      String servletClass) {
        // will do class for name and set init params
        Wrapper sw = ctx.createWrapper();
        sw.setServletClass(servletClass);
        sw.setName(servletName);
        ctx.addChild(sw);

        return sw;
    }

    /**
     * Add an existing Servlet to the context with no class.forName or
     * initialisation.
     * @param contextPath   Context to add Servlet to
     * @param servletName   Servlet name (used in mappings)
     * @param servlet       The Servlet to add
     * @return The wrapper for the servlet
     */
    public Wrapper addServlet(String contextPath,
            String servletName,
            Servlet servlet) {
        Container ctx = getHost().findChild(contextPath);
        return addServlet((Context) ctx, servletName, servlet);
    }

    /**
     * Static version of {@link #addServlet(String, String, Servlet)}.
     * @param ctx           Context to add Servlet to
     * @param servletName   Servlet name (used in mappings)
     * @param servlet       The Servlet to add
     * @return The wrapper for the servlet
     */
    public static Wrapper addServlet(Context ctx,
                                      String servletName,
                                      Servlet servlet) {
        // will do class for name and set init params
        Wrapper sw = new ExistingStandardWrapper(servlet);
        sw.setName(servletName);
        ctx.addChild(sw);

        return sw;
    }


    /**
     * Initialize the server.
     *
     * @throws LifecycleException Init error
     */
    public void init() throws LifecycleException {
        getServer();
        getConnector();
        server.init();
    }


    /**
     * Start the server.
     *
     * @throws LifecycleException Start error
     */
    public void start() throws LifecycleException {
        getServer();
        //获得connector容器，并且将得到的connector容器设置到service容器中
        getConnector();
        server.start();
    }

    /**
     * Stop the server.
     *
     * @throws LifecycleException Stop error
     */
    public void stop() throws LifecycleException {
        getServer();
        server.stop();
    }


    /**
     * Destroy the server. This object cannot be used once this method has been
     * called.
     *
     * @throws LifecycleException Destroy error
     */
    public void destroy() throws LifecycleException {
        getServer();
        server.destroy();
        // Could null out objects here
    }

    /**
     * Add a user for the in-memory realm. All created apps use this
     * by default, can be replaced using setRealm().
     * @param user The user name
     * @param pass The password
     */
    public void addUser(String user, String pass) {
        userPass.put(user, pass);
    }

    /**
     * Add a role to a user.
     * @see #addUser(String, String)
     * @param user The user name
     * @param role The role name
     */
    public void addRole(String user, String role) {
        List<String> roles = userRoles.get(user);
        if (roles == null) {
            roles = new ArrayList<>();
            userRoles.put(user, roles);
        }
        roles.add(role);
    }

    // ------- Extra customization -------
    // You can tune individual Tomcat objects, using internal APIs

    /**
     * Get the default http connector. You can set more
     * parameters - the port is already initialized.
     *
     * <p>
     * Alternatively, you can construct a Connector and set any params,
     * then call addConnector(Connector)
     *
     * @return A connector object that can be customized
     */
    public Connector getConnector() {
        Service service = getService();
        if (service.findConnectors().length > 0) {
            return service.findConnectors()[0];
        }

        if (defaultConnectorCreated) {
            return null;
        }
        // The same as in standard Tomcat configuration.
        // This creates an APR HTTP connector if AprLifecycleListener has been
        // configured (created) and Tomcat Native library is available.
        // Otherwise it creates a NIO HTTP connector.
        Connector connector = new Connector("HTTP/1.1");
        connector.setPort(port);
        service.addConnector(connector);
        defaultConnectorCreated = true;
        return connector;
    }

    public void setConnector(Connector connector) {
        defaultConnectorCreated = true;
        Service service = getService();
        boolean found = false;
        for (Connector serviceConnector : service.findConnectors()) {
            if (connector == serviceConnector) {
                found = true;
                break;
            }
        }
        if (!found) {
            service.addConnector(connector);
        }
    }

    /**
     * Get the service object. Can be used to add more
     * connectors and few other global settings.
     * @return The service
     */
    public Service getService() {
        return getServer().findServices()[0];
    }

    /**
     * Sets the current host - all future webapps will
     * be added to this host. When tomcat starts, the
     * host will be the default host.
     *
     * @param host The current host
     */
    public void setHost(Host host) {
        Engine engine = getEngine();
        boolean found = false;
        for (Container engineHost : engine.findChildren()) {
            if (engineHost == host) {
                found = true;
                break;
            }
        }
        if (!found) {
            engine.addChild(host);
        }
    }

    public Host getHost() {
        //将每一层的容器都new 出来
        Engine engine = getEngine();
        if (engine.findChildren().length > 0) {
            return (Host) engine.findChildren()[0];
        }

        Host host = new StandardHost();
        host.setName(hostname);
        //维护tomcat中的父子容器
        getEngine().addChild(host);
        return host;
    }

    /**
     * Access to the engine, for further customization.
     * @return The engine
     */
    public Engine getEngine() {
        //getServer中创建server容器
        Service service = getServer().findServices()[0];
        if (service.getContainer() != null) {
            return service.getContainer();
        }
        Engine engine = new StandardEngine();
        engine.setName( "Tomcat" );
        engine.setDefaultHost(hostname);
        engine.setRealm(createDefaultRealm());
        service.setContainer(engine);
        return engine;
    }

    /**
     * Get the server object. You can add listeners and few more
     * customizations. JNDI is disabled by default.
     * @return The Server
     */
    public Server getServer() {

        if (server != null) {
            return server;
        }

        System.setProperty("catalina.useNaming", "false");

        //最外层server容器
        server = new StandardServer();

        initBaseDir();

        server.setPort( -1 );
        //创建service 容器
        Service service = new StandardService();
        service.setName("Tomcat");
        //将service容器添加到server容器中，也证明了service容器时server容器的一个子容器
        //server容器 持有 service容器的一个引用
        server.addService(service);
        return server;
    }

    /**
     * @param host The host in which the context will be deployed
     * @param contextPath The context mapping to use, "" for root context.
     * @param dir Base directory for the context, for static files.
     *  Must exist, relative to the server home
     * @return the deployed context
     * @see #addContext(String, String)
     */
    public Context addContext(Host host, String contextPath, String dir) {
        return addContext(host, contextPath, contextPath, dir);
    }

    /**
     * @param host The host in which the context will be deployed
     * @param contextPath The context mapping to use, "" for root context.
     * @param contextName The context name
     * @param dir Base directory for the context, for static files.
     *  Must exist, relative to the server home
     * @return the deployed context
     * @see #addContext(String, String)
     */
    public Context addContext(Host host, String contextPath, String contextName,
            String dir) {
        silence(host, contextName);
        Context ctx = createContext(host, contextPath);
        ctx.setName(contextName);
        ctx.setPath(contextPath);
        ctx.setDocBase(dir);
        ctx.addLifecycleListener(new FixContextListener());

        if (host == null) {
            getHost().addChild(ctx);
        } else {
            host.addChild(ctx);
        }
        return ctx;
    }


    /**
     * This is equivalent to adding a web application to a Host's appBase
     * (usually Tomcat's webapps directory). By default, the equivalent of the
     * default web.xml will be applied to the web application (see
     * {@link #initWebappDefaults(String)}). This may be prevented by calling
     * {@link #setAddDefaultWebXmlToWebapp(boolean)} with {@code false}. Any
     * <code>WEB-INF/web.xml</code> and <code>META-INF/context.xml</code>
     * packaged with the application will always be processed and normal web
     * fragment and {@link javax.servlet.ServletContainerInitializer} processing
     * will always be applied.
     *
     * @param host        The host in which the context will be deployed
     * @param contextPath The context mapping to use, "" for root context.
     * @param docBase     Base directory for the context, for static files. Must
     *                        exist, relative to the server home
     *
     * @return the deployed context
     *
     * tomcat的架构就是一个多级容器的结构，我们可以通过server.xml分析出来
     * 1、Server   --->  Service   -->   Connector   Engine addChild--->   context(servlet容器)
     */

    public Context  addWebapp(Host host, String contextPath, String docBase) {
        //通过反射获得一个监听器
        LifecycleListener listener = null;
        try {
            Class<?> clazz = Class.forName(getHost().getConfigClass());
            listener = (LifecycleListener) clazz.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            // Wrap in IAE since we can't easily change the method signature to
            // to throw the specific checked exceptions
            throw new IllegalArgumentException(e);
        }

        return addWebapp(host, contextPath, docBase, listener);
    }


    /**
     * @param host The host in which the context will be deployed
     * @param contextPath The context mapping to use, "" for root context.
     * @param docBase Base directory for the context, for static files.
     *  Must exist, relative to the server home
     * @param config Custom context configurator helper
     * @return the deployed context
     * @see #addWebapp(String, String)
     *
     * @deprecated Use {@link
     *             #addWebapp(Host, String, String, LifecycleListener)} instead
     */
    @Deprecated
    public Context addWebapp(Host host, String contextPath, String docBase, ContextConfig config) {
        return addWebapp(host, contextPath, docBase, (LifecycleListener) config);
    }


    /**
     * This is equivalent to adding a web application to a Host's appBase
     * (usually Tomcat's webapps directory). By default, the equivalent of the
     * default web.xml will be applied to the web application (see
     * {@link #initWebappDefaults(String)}). This may be prevented by calling
     * {@link #setAddDefaultWebXmlToWebapp(boolean)} with {@code false}. Any
     * <code>WEB-INF/web.xml</code> and <code>META-INF/context.xml</code>
     * packaged with the application will always be processed and normal web
     * fragment and {@link javax.servlet.ServletContainerInitializer} processing
     * will always be applied.
     *
     * @param host        The host in which the context will be deployed
     * @param contextPath The context mapping to use, "" for root context.
     * @param docBase     Base directory for the context, for static files. Must
     *                        exist, relative to the server home
     * @param config      Custom context configuration helper. Any configuration
     *                        will be in addition to equivalent of the default
     *                        web.xml configuration described above.
     *
     * @return the deployed context
     */
    public Context addWebapp(Host host, String contextPath, String docBase,
            LifecycleListener config) {

        silence(host, contextPath);

        //获得一个context容器
        Context ctx = createContext(host, contextPath);
        ctx.setPath(contextPath);
        ctx.setDocBase(docBase);

        if (addDefaultWebXmlToWebapp) {
            ctx.addLifecycleListener(getDefaultWebXmlListener());
        }

        ctx.setConfigFile(getWebappConfigFile(docBase, contextPath));
        //把监听器添加到context中去
        ctx.addLifecycleListener(config);

        if (addDefaultWebXmlToWebapp && (config instanceof ContextConfig)) {
            // prevent it from looking ( if it finds one - it'll have dup error )
            ((ContextConfig) config).setDefaultWebXml(noDefaultWebXmlPath());
        }

        if (host == null) {
            //getHost会逐层创建容器，并维护容器父子关系
            getHost().addChild(ctx);
        } else {
            host.addChild(ctx);
        }

        return ctx;
    }

    /**
     * Return a listener that provides the required configuration items for JSP
     * processing. From the standard Tomcat global web.xml. Pass this to
     * {@link Context#addLifecycleListener(LifecycleListener)} and then pass the
     * result of {@link #noDefaultWebXmlPath()} to
     * {@link ContextConfig#setDefaultWebXml(String)}.
     * @return a listener object that configures default JSP processing.
     */
    public LifecycleListener getDefaultWebXmlListener() {
        return new DefaultWebXmlListener();
    }

    /**
     * @return a pathname to pass to
     * {@link ContextConfig#setDefaultWebXml(String)} when using
     * {@link #getDefaultWebXmlListener()}.
     */
    public String noDefaultWebXmlPath() {
        return Constants.NoDefaultWebXml;
    }

    // ---------- Helper methods and classes -------------------

    /**
     * Create an in-memory realm. You can replace it for contexts with a real
     * one. The Realm created here will be added to the Engine by default and
     * may be replaced at the Engine level or over-ridden (as per normal Tomcat
     * behaviour) at the Host or Context level.
     * @return a realm instance
     */
    protected Realm createDefaultRealm() {
        return new RealmBase() {
            @Override
            @Deprecated
            protected String getName() {
                return "Simple";
            }

            @Override
            protected String getPassword(String username) {
                return userPass.get(username);
            }

            @Override
            protected Principal getPrincipal(String username) {
                Principal p = userPrincipals.get(username);
                if (p == null) {
                    String pass = userPass.get(username);
                    if (pass != null) {
                        p = new GenericPrincipal(username, pass,
                                userRoles.get(username));
                        userPrincipals.put(username, p);
                    }
                }
                return p;
            }

        };
    }


    protected void initBaseDir() {
        String catalinaHome = System.getProperty(Globals.CATALINA_HOME_PROP);
        if (basedir == null) {
            basedir = System.getProperty(Globals.CATALINA_BASE_PROP);
        }
        if (basedir == null) {
            basedir = catalinaHome;
        }
        if (basedir == null) {
            // Create a temp dir.
            basedir = System.getProperty("user.dir") + "/tomcat." + port;
        }

        File baseFile = new File(basedir);
        if (baseFile.exists()) {
            if (!baseFile.isDirectory()) {
                throw new IllegalArgumentException(sm.getString("tomcat.baseDirNotDir", baseFile));
            }
        } else {
            if (!baseFile.mkdirs()) {
                // Failed to create base directory
                throw new IllegalStateException(sm.getString("tomcat.baseDirMakeFail", baseFile));
            }
            /*
             * If file permissions were going to be set on the newly created
             * directory, this is the place to do it. However, even simple
             * calls such as File.setReadable(boolean,boolean) behaves
             * differently on different platforms. Therefore, setBaseDir
             * documents that the user needs to do this.
             */
        }
        try {
            baseFile = baseFile.getCanonicalFile();
        } catch (IOException e) {
            baseFile = baseFile.getAbsoluteFile();
        }
        server.setCatalinaBase(baseFile);
        System.setProperty(Globals.CATALINA_BASE_PROP, baseFile.getPath());
        basedir = baseFile.getPath();

        if (catalinaHome == null) {
            server.setCatalinaHome(baseFile);
        } else {
            File homeFile = new File(catalinaHome);
            if (!homeFile.isDirectory() && !homeFile.mkdirs()) {
                // Failed to create home directory
                throw new IllegalStateException(sm.getString("tomcat.homeDirMakeFail", homeFile));
            }
            try {
                homeFile = homeFile.getCanonicalFile();
            } catch (IOException e) {
                homeFile = homeFile.getAbsoluteFile();
            }
            server.setCatalinaHome(homeFile);
        }
        System.setProperty(Globals.CATALINA_HOME_PROP,
                server.getCatalinaHome().getPath());
    }

    static final String[] silences = new String[] {
        "org.apache.coyote.http11.Http11NioProtocol",
        "org.apache.catalina.core.StandardService",
        "org.apache.catalina.core.StandardEngine",
        "org.apache.catalina.startup.ContextConfig",
        "org.apache.catalina.core.ApplicationContext",
        "org.apache.catalina.core.AprLifecycleListener"
    };

    private boolean silent = false;

    /**
     * Controls if the loggers will be silenced or not.
     * @param silent    <code>true</code> sets the log level to WARN for the
     *                  loggers that log information on Tomcat start up. This
     *                  prevents the usual startup information being logged.
     *                  <code>false</code> sets the log level to the default
     *                  level of INFO.
     */
    public void setSilent(boolean silent) {
        this.silent = silent;
        for (String s : silences) {
            Logger logger = Logger.getLogger(s);
            pinnedLoggers.put(s, logger);
            if (silent) {
                logger.setLevel(Level.WARNING);
            } else {
                logger.setLevel(Level.INFO);
            }
        }
    }

    private void silence(Host host, String contextPath) {
        String loggerName = getLoggerName(host, contextPath);
        Logger logger = Logger.getLogger(loggerName);
        pinnedLoggers.put(loggerName, logger);
        if (silent) {
            logger.setLevel(Level.WARNING);
        } else {
            logger.setLevel(Level.INFO);
        }
    }


    /**
     * By default, when calling addWebapp() to create a Context, the settings from
     * from the default web.xml are added to the context.  Calling this method with
     * a <code>false</code> value prior to calling addWebapp() allows to opt out of
     * the default settings. In that event you will need to add the configurations
     * yourself,  either programmatically or by using web.xml deployment descriptors.
     * @param addDefaultWebXmlToWebapp <code>false</code> will prevent the class from
     *                                 automatically adding the default settings when
     *                                 calling addWebapp().
     *                                 <code>true</code> will add the default settings
     *                                 and is the default behavior.
     * @see #addWebapp(Host, String, String, LifecycleListener)
     */
    public void setAddDefaultWebXmlToWebapp(boolean addDefaultWebXmlToWebapp){
        this.addDefaultWebXmlToWebapp = addDefaultWebXmlToWebapp;
    }


    /*
     * Uses essentially the same logic as {@link ContainerBase#logName()}.
     */
    private String getLoggerName(Host host, String contextName) {
        if (host == null) {
            host = getHost();
        }
        StringBuilder loggerName = new StringBuilder();
        loggerName.append(ContainerBase.class.getName());
        loggerName.append(".[");
        // Engine name
        loggerName.append(host.getParent().getName());
        loggerName.append("].[");
        // Host name
        loggerName.append(host.getName());
        loggerName.append("].[");
        // Context name
        if (contextName == null || contextName.equals("")) {
            loggerName.append('/');
        } else if (contextName.startsWith("##")) {
            loggerName.append('/');
            loggerName.append(contextName);
        }
        loggerName.append(']');

        return loggerName.toString();
    }

    /**
     * Create the configured {@link Context} for the given <code>host</code>.
     * The default constructor of the class that was configured with
     * {@link StandardHost#setContextClass(String)} will be used
     *
     * @param host
     *            host for which the {@link Context} should be created, or
     *            <code>null</code> if default host should be used
     * @param url
     *            path of the webapp which should get the {@link Context}
     * @return newly created {@link Context}
     */
    private Context createContext(Host host, String url) {
        //获得context容器，context容器就是我们部署应用的容器
        String contextClass = StandardContext.class.getName();
        if (host == null) {
            host = this.getHost();
        }
        if (host instanceof StandardHost) {
            contextClass = ((StandardHost) host).getContextClass();
        }
        try {
            return (Context) Class.forName(contextClass).getConstructor()
                    .newInstance();
        } catch (InstantiationException | IllegalAccessException
                | IllegalArgumentException | InvocationTargetException
                | NoSuchMethodException | SecurityException
                | ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Can't instantiate context-class " + contextClass
                            + " for host " + host + " and url "
                            + url, e);
        }
    }

    /**
     * Enables JNDI naming which is disabled by default. Server must implement
     * {@link Lifecycle} in order for the {@link NamingContextListener} to be
     * used.
     *
     */
    public void enableNaming() {
        // Make sure getServer() has been called as that is where naming is
        // disabled
        getServer();
        server.addLifecycleListener(new NamingContextListener());

        System.setProperty("catalina.useNaming", "true");

        String value = "org.apache.naming";
        String oldValue =
            System.getProperty(javax.naming.Context.URL_PKG_PREFIXES);
        if (oldValue != null) {
            if (oldValue.contains(value)) {
                value = oldValue;
            } else {
                value = value + ":" + oldValue;
            }
        }
        System.setProperty(javax.naming.Context.URL_PKG_PREFIXES, value);

        value = System.getProperty
            (javax.naming.Context.INITIAL_CONTEXT_FACTORY);
        if (value == null) {
            System.setProperty
                (javax.naming.Context.INITIAL_CONTEXT_FACTORY,
                 "org.apache.naming.java.javaURLContextFactory");
        }
    }


    /**
     * Provide default configuration for a context. This is broadly the
     * programmatic equivalent of the default web.xml and provides the following
     * features:
     * <ul>
     * <li>Default servlet mapped to "/"</li>
     * <li>JSP servlet mapped to "*.jsp" and ""*.jspx"</li>
     * <li>Session timeout of 30 minutes</li>
     * <li>MIME mappings (subset of those in conf/web.xml)</li>
     * <li>Welcome files</li>
     * </ul>
     * TODO: Align the MIME mappings with conf/web.xml - possibly via a common
     *       file.
     *
     * @param contextPath   The path of the context to set the defaults for
     */
    public void initWebappDefaults(String contextPath) {
        Container ctx = getHost().findChild(contextPath);
        initWebappDefaults((Context) ctx);
    }


    /**
     * Static version of {@link #initWebappDefaults(String)}.
     *
     * @param ctx   The context to set the defaults for
     */
    public static void initWebappDefaults(Context ctx) {
        // Default servlet
        Wrapper servlet = addServlet(
                ctx, "default", "org.apache.catalina.servlets.DefaultServlet");
        servlet.setLoadOnStartup(1);
        servlet.setOverridable(true);

        // JSP servlet (by class name - to avoid loading all deps)
        servlet = addServlet(
                ctx, "jsp", "org.apache.jasper.servlet.JspServlet");
        servlet.addInitParameter("fork", "false");
        servlet.setLoadOnStartup(3);
        servlet.setOverridable(true);

        // Servlet mappings
        ctx.addServletMappingDecoded("/", "default");
        ctx.addServletMappingDecoded("*.jsp", "jsp");
        ctx.addServletMappingDecoded("*.jspx", "jsp");

        // Sessions
        ctx.setSessionTimeout(30);

        // MIME type mappings
        addDefaultMimeTypeMappings(ctx);

        // Welcome files
        ctx.addWelcomeFile("index.html");
        ctx.addWelcomeFile("index.htm");
        ctx.addWelcomeFile("index.jsp");
    }


    /**
     * Add the default MIME type mappings to the provide Context.
     *
     * @param context The web application to which the default MIME type
     *                mappings should be added.
     */
    public static void addDefaultMimeTypeMappings(Context context) {
        Properties defaultMimeMappings = new Properties();
        try (InputStream is = Tomcat.class.getResourceAsStream("MimeTypeMappings.properties")) {
            defaultMimeMappings.load(is);
            for (Map.Entry<Object, Object>  entry: defaultMimeMappings.entrySet()) {
                context.addMimeMapping((String) entry.getKey(), (String) entry.getValue());
            }
        } catch (IOException e) {
            throw new IllegalStateException(sm.getString("tomcat.defaultMimeTypeMappingsFail"), e);
        }
    }


    /**
     * Fix startup sequence - required if you don't use web.xml.
     *
     * <p>
     * The start() method in context will set 'configured' to false - and
     * expects a listener to set it back to true.
     */
    public static class FixContextListener implements LifecycleListener {

        @Override
        public void lifecycleEvent(LifecycleEvent event) {
            try {
                Context context = (Context) event.getLifecycle();
                if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
                    context.setConfigured(true);

                    // Process annotations
                    WebAnnotationSet.loadApplicationAnnotations(context);

                    // LoginConfig is required to process @ServletSecurity
                    // annotations
                    if (context.getLoginConfig() == null) {
                        context.setLoginConfig(new LoginConfig("NONE", null, null, null));
                        context.getPipeline().addValve(new NonLoginAuthenticator());
                    }
                }
            } catch (ClassCastException e) {
            }
        }
    }


    /**
     * Fix reload - required if reloading and using programmatic configuration.
     * When a context is reloaded, any programmatic configuration is lost. This
     * listener sets the equivalent of conf/web.xml when the context starts.
     */
    public static class DefaultWebXmlListener implements LifecycleListener {
        @Override
        public void lifecycleEvent(LifecycleEvent event) {
            if (Lifecycle.BEFORE_START_EVENT.equals(event.getType())) {
                initWebappDefaults((Context) event.getLifecycle());
            }
        }
    }


    /**
     * Helper class for wrapping existing servlets. This disables servlet
     * lifecycle and normal reloading, but also reduces overhead and provide
     * more direct control over the servlet.
     */
    public static class ExistingStandardWrapper extends StandardWrapper {
        private final Servlet existing;

        @SuppressWarnings("deprecation")
        public ExistingStandardWrapper( Servlet existing ) {
            this.existing = existing;
            if (existing instanceof javax.servlet.SingleThreadModel) {
                singleThreadModel = true;
                instancePool = new Stack<>();
            }
            this.asyncSupported = hasAsync(existing);
        }

        private static boolean hasAsync(Servlet existing) {
            boolean result = false;
            Class<?> clazz = existing.getClass();
            WebServlet ws = clazz.getAnnotation(WebServlet.class);
            if (ws != null) {
                result = ws.asyncSupported();
            }
            return result;
        }

        @Override
        public synchronized Servlet loadServlet() throws ServletException {
            if (singleThreadModel) {
                Servlet instance;
                try {
                    instance = existing.getClass().getConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new ServletException(e);
                }
                instance.init(facade);
                return instance;
            } else {
                if (!instanceInitialized) {
                    existing.init(facade);
                    instanceInitialized = true;
                }
                return existing;
            }
        }
        @Override
        public long getAvailable() {
            return 0;
        }
        @Override
        public boolean isUnavailable() {
            return false;
        }
        @Override
        public Servlet getServlet() {
            return existing;
        }
        @Override
        public String getServletClass() {
            return existing.getClass().getName();
        }
    }

    protected URL getWebappConfigFile(String path, String contextName) {
        File docBase = new File(path);
        if (docBase.isDirectory()) {
            return getWebappConfigFileFromDirectory(docBase, contextName);
        } else {
            return getWebappConfigFileFromWar(docBase, contextName);
        }
    }

    private URL getWebappConfigFileFromDirectory(File docBase, String contextName) {
        URL result = null;
        File webAppContextXml = new File(docBase, Constants.ApplicationContextXml);
        if (webAppContextXml.exists()) {
            try {
                result = webAppContextXml.toURI().toURL();
            } catch (MalformedURLException e) {
                Logger.getLogger(getLoggerName(getHost(), contextName)).log(Level.WARNING,
                        "Unable to determine web application context.xml " + docBase, e);
            }
        }
        return result;
    }

    private URL getWebappConfigFileFromWar(File docBase, String contextName) {
        URL result = null;
        try (JarFile jar = new JarFile(docBase)) {
            JarEntry entry = jar.getJarEntry(Constants.ApplicationContextXml);
            if (entry != null) {
                result = UriUtil.buildJarUrl(docBase, Constants.ApplicationContextXml);
            }
        } catch (IOException e) {
            Logger.getLogger(getLoggerName(getHost(), contextName)).log(Level.WARNING,
                    "Unable to determine web application context.xml " + docBase, e);
        }
        return result;
    }
}
