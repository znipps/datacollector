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
package com.streamsets.pipeline.stage.destination.elasticsearch;

import com.streamsets.pipeline.api.Label;
import com.streamsets.pipeline.lib.operation.OperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum ElasticSearchOperationType implements Label {
  INDEX(OperationType.UPSERT_CODE),
  CREATE(OperationType.INSERT_CODE),
  UPDATE(OperationType.UPDATE_CODE),
  DELETE(OperationType.DELETE_CODE),
  ;

  final int code;
  private static final Logger LOG = LoggerFactory.getLogger(ElasticSearchTarget.class);

  ElasticSearchOperationType(int code) {
    this.code = code;
  }

  @Override
  public String getLabel() {
    switch (this.code) {
      case OperationType.UPSERT_CODE:
        return "INDEX";
      case OperationType.INSERT_CODE:
        return "CREATE";
      case OperationType.UPDATE_CODE:
        return "UPDATE";
      case OperationType.DELETE_CODE:
        return "DELETE";
      default:
        return null;
    }
  }

  /**
   * Take an numeric operation code in String and check if the number is
   * valid operation code.
   * The operation code must be numeric: 1(insert), 2(update), 3(delete), etc,
   * @param op Numeric operation code in String
   * @return Operation code in int, -1 if invalid number
   */
  static int convertToIntCode(String op)  {
    try {
      int intOp = Integer.parseInt(op);
      for (ElasticSearchOperationType type : ElasticSearchOperationType.values()) {
        if (type.code == intOp) {
          return type.code;
        }
      }
      LOG.error("Unsupported operation by Elasticsearch Destination: Operation Code {}", op);
      return -1;
    } catch (NumberFormatException ex) {
      throw new NumberFormatException("Operation code must be numeric value. " + ex.getMessage());
    }
  }
}
