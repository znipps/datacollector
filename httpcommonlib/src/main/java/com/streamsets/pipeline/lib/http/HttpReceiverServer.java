/**
 * Copyright 2016 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.lib.http;

import com.google.common.annotations.VisibleForTesting;
import com.streamsets.pipeline.api.Stage;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

@SuppressWarnings({"squid:S2095", "squid:S00112"})
public class HttpReceiverServer {
  private static final Logger LOG = LoggerFactory.getLogger(HttpReceiverServer.class);

  private final HttpConfigs configs;
  private final HttpReceiver receiver;
  private final BlockingQueue<Exception> errorQueue;

  private Server httpServer;
  private HttpReceiverServlet servlet;

  public HttpReceiverServer(HttpConfigs configs, HttpReceiver receiver, BlockingQueue<Exception> errorQueue) {
    this.configs = configs;
    this.receiver =receiver;
    this.errorQueue = errorQueue;
  }

  @VisibleForTesting
  int getJettyServerThreads(int maxConcurrentRequests) {
    // per Jetty hardcoded logic, the minimum number of threads we can have is determined by the following formula
    int cores = Runtime.getRuntime().availableProcessors();
    int acceptors = Math.max(1, Math.min(4, cores / 8));
    int selectors = (cores + 1) / 2;
    return acceptors + selectors + maxConcurrentRequests;
  }

  @VisibleForTesting
  int getJettyServerMinThreads() {
    return Math.max(configs.getMaxConcurrentRequests() / 2, getJettyServerThreads(1));
  }

  @VisibleForTesting
  int getJettyServerMaxThreads() {
    return getJettyServerThreads(configs.getMaxConcurrentRequests());
  }

  public List<Stage.ConfigIssue> init(Stage.Context context) {
    List<Stage.ConfigIssue> issues = new ArrayList<>();
    try {
      int maxThreads = getJettyServerMaxThreads();
      int minThreads = getJettyServerMinThreads();
      QueuedThreadPool threadPool =
          new QueuedThreadPool(maxThreads, minThreads, 60000, new ArrayBlockingQueue<Runnable>(maxThreads));
      threadPool.setName("http-receiver-server:" + context.getPipelineInfo().get(0).getInstanceName());
      threadPool.setDaemon(true);
      Server server = new Server(threadPool);

      ServerConnector connector;
      if (configs.isSslEnabled()) {
        LOG.debug("Configuring HTTPS");
        HttpConfiguration httpsConf = new HttpConfiguration();
        httpsConf.addCustomizer(new SecureRequestCustomizer());
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(configs.getKeyStoreFilePath());
        sslContextFactory.setKeyStorePassword(configs.getKeyStorePassword());
        sslContextFactory.setKeyManagerPassword(configs.getKeyStorePassword());
        connector = new ServerConnector(server,
            new SslConnectionFactory(sslContextFactory, "http/1.1"),
            new HttpConnectionFactory(httpsConf)
        );
      } else {
        LOG.debug("Configuring HTTP");
        connector = new ServerConnector(server);
      }
      connector.setPort(configs.getPort());
      server.setConnectors(new Connector[]{connector});

      servlet = new HttpReceiverServlet(context, receiver, errorQueue);
      ServletContextHandler contextHandler = new ServletContextHandler();
      // CORS Handling
      FilterHolder crossOriginFilter = new FilterHolder(CrossOriginFilter.class);
      Map<String, String> params = new HashMap<>();
      params.put(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
      params.put(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "*");
      crossOriginFilter.setInitParameters(params);
      contextHandler.addFilter(crossOriginFilter, "/*", EnumSet.of(DispatcherType.REQUEST));

      contextHandler.addServlet(new ServletHolder(servlet), servlet.getReceiver().getUriPath());
      contextHandler.setContextPath("/");
      server.setHandler(contextHandler);
      server.start();

      LOG.debug("Running, port '{}', TLS '{}'", configs.getPort(), configs.isSslEnabled());

      httpServer = server;
    } catch (Exception ex) {
      issues.add(context.createConfigIssue("HTTP", "", Errors.HTTP_SERVER_ORIG_20, ex.toString()));
    }
    return issues;
  }

  public void destroy() {
    LOG.debug("Shutting down, port '{}', TLS '{}'", configs.getPort(), configs.isSslEnabled());
    if (httpServer != null) {
      try {
        servlet.setShuttingDown();
        httpServer.stop();
      } catch (Exception ex) {
        LOG.warn("Error while shutting down: {}", ex.toString(), ex);
      }
      httpServer = null;
    }
  }

}
