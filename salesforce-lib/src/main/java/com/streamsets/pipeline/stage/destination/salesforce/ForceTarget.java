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
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.salesforce;

import com.google.common.collect.Multimap;
import com.sforce.async.AsyncApiException;
import com.sforce.async.BulkConnection;
import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import com.sforce.ws.SessionRenewer;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.Target;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.lib.el.ELUtils;
import com.streamsets.pipeline.lib.salesforce.ForceTargetConfigBean;
import com.streamsets.pipeline.lib.salesforce.ForceUtils;
import com.streamsets.pipeline.lib.salesforce.Errors;
import com.streamsets.pipeline.lib.salesforce.ForceFieldMapping;
import com.streamsets.pipeline.stage.common.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.common.ErrorRecordHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * This target writes records to Salesforce Wave Analytics
 */
public class ForceTarget extends BaseTarget {

  private static final Logger LOG = LoggerFactory.getLogger(ForceTarget.class);
  private static final String SOBJECT_NAME = "sObjectNameTemplate";
  private ErrorRecordHandler errorRecordHandler;

  private final ForceTargetConfigBean conf;
  private final boolean useCompression;
  private final boolean showTrace;

  private ForceWriter writer;
  private SortedMap<String, String> fieldMappings;
  private PartnerConnection partnerConnection;
  private BulkConnection bulkConnection;
  private ELVars sObjectNameVars;
  private ELEval sObjectNameEval;

  public ForceTarget(
      ForceTargetConfigBean conf, boolean useCompression, boolean showTrace
  ) {
    this.conf = conf;
    this.useCompression = useCompression;
    this.showTrace = showTrace;
  }

  // Renew the Salesforce session on timeout
  private class ForceSessionRenewer implements SessionRenewer {
    @Override
    public SessionRenewalHeader renewSession(ConnectorConfig config) throws ConnectionException {
      partnerConnection = Connector.newConnection(ForceUtils.getPartnerConfig(conf, new ForceSessionRenewer()));

      SessionRenewalHeader header = new SessionRenewalHeader();
      header.name = new QName("urn:enterprise.soap.sforce.com", "SessionHeader");
      header.headerElement = partnerConnection.getSessionHeader();
      return header;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected List<ConfigIssue> init() {
    // Validate configuration values and open any required resources.
    List<ConfigIssue> issues = super.init();
    Target.Context context = getContext();

    errorRecordHandler = new DefaultErrorRecordHandler(context);

    sObjectNameVars = getContext().createELVars();
    sObjectNameEval = context.createELEval(SOBJECT_NAME);
    ELUtils.validateExpression(sObjectNameEval,
        sObjectNameVars,
        conf.sObjectNameTemplate,
        context,
        Groups.FORCE.getLabel(),
        SOBJECT_NAME,
        Errors.FORCE_12,
        String.class,
        issues
    );

    if (issues.isEmpty()) {
      fieldMappings = new TreeMap<>();
      for (ForceFieldMapping mapping : conf.fieldMapping) {
        fieldMappings.put(mapping.salesforceField, mapping.sdcField);
      }

      try {
        ConnectorConfig partnerConfig = ForceUtils.getPartnerConfig(conf, new ForceSessionRenewer());
        partnerConnection = Connector.newConnection(partnerConfig);
        bulkConnection = ForceUtils.getBulkConnection(partnerConfig, conf);
        LOG.info("Successfully authenticated as {}", conf.username);
      } catch (ConnectionException | AsyncApiException ce) {
        issues.add(getContext().createConfigIssue(Groups.FORCE.name(),
            "connectorConfig",
            Errors.FORCE_00,
            ForceUtils.getExceptionCode(ce) + ", " + ForceUtils.getExceptionMessage(ce)
        ));
      }

      if (conf.useBulkAPI) {
        writer = new ForceBulkWriter(fieldMappings, bulkConnection, getContext());
      } else {
        writer = new ForceSoapWriter(fieldMappings, partnerConnection);
      }
    }

    // If issues is not empty, the UI will inform the user of each configuration issue in the list.
    return issues;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void destroy() {
    super.destroy();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void write(Batch batch) throws StageException {
    Multimap<String, Record> partitions = ELUtils.partitionBatchByExpression(sObjectNameEval,
        sObjectNameVars,
        conf.sObjectNameTemplate,
        batch
    );
    Set<String> sObjectNames = partitions.keySet();
    for (String sObjectName : sObjectNames) {
      List<OnRecordErrorException> errors = writer.writeBatch(sObjectName, partitions.get(sObjectName));
      for (OnRecordErrorException error : errors) {
        errorRecordHandler.onError(error);
      }
    }
  }
}
