/*
 * Copyright 2014 mango.jfaster.org
 *
 * The Mango Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.jfaster.mango.operator;

import org.jfaster.mango.annotation.UseMaster;
import org.jfaster.mango.binding.DefaultParameterContext;
import org.jfaster.mango.binding.InvocationContextFactory;
import org.jfaster.mango.binding.ParameterContext;
import org.jfaster.mango.datasource.DataSourceFactoryGroup;
import org.jfaster.mango.datasource.DataSourceType;
import org.jfaster.mango.descriptor.MethodDescriptor;
import org.jfaster.mango.descriptor.ParameterDescriptor;
import org.jfaster.mango.jdbc.JdbcOperations;
import org.jfaster.mango.jdbc.JdbcTemplate;
import org.jfaster.mango.operator.generator.DataSourceGenerator;
import org.jfaster.mango.operator.generator.DataSourceGeneratorFactory;
import org.jfaster.mango.operator.generator.TableGenerator;
import org.jfaster.mango.operator.generator.TableGeneratorFactory;
import org.jfaster.mango.page.InvocationPageHandler;
import org.jfaster.mango.page.PageHandler;
import org.jfaster.mango.parser.ASTRootNode;
import org.jfaster.mango.parser.SqlParser;
import org.jfaster.mango.util.jdbc.OperatorType;
import org.jfaster.mango.util.jdbc.SQLType;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ash
 */
class OperatorFactory {

  private final PageHandler pageHandler;
  private final JdbcOperations jdbcOperations;
  private final Config config;
  private final TableGeneratorFactory tableGeneratorFactory;
  private final DataSourceGeneratorFactory dataSourceGeneratorFactory;

  OperatorFactory(DataSourceFactoryGroup dataSourceFactoryGroup,
                         PageHandler pageHandler, Config config) {
    this.pageHandler = pageHandler;
    this.config = config;
    this.jdbcOperations = new JdbcTemplate();
    this.tableGeneratorFactory = new TableGeneratorFactory();
    this.dataSourceGeneratorFactory = new DataSourceGeneratorFactory(dataSourceFactoryGroup);
  }

  AbstractOperator getOperator(MethodDescriptor md) {
    ASTRootNode rootNode = SqlParser.parse(md.getSQL()).init(); // ????????????????????????
    List<ParameterDescriptor> pds = md.getParameterDescriptors(); // ??????????????????
    OperatorType operatorType = getOperatorType(pds, rootNode);
    if (operatorType == OperatorType.BATCHUPDATE) { // ????????????????????????ParameterDescriptorList
      ParameterDescriptor pd = pds.get(0);
      pds = new ArrayList<ParameterDescriptor>(1);
      pds.add(ParameterDescriptor.create(0, pd.getMappedClass(), pd.getAnnotations(), pd.getName()));
    }

    ParameterContext context = DefaultParameterContext.create(pds);
    rootNode.expandParameter(context); // ???????????????????????????
    rootNode.checkAndBind(context); // ????????????????????????????????????

    // ??????????????????
    boolean isSqlUseGlobalTable = !rootNode.getASTGlobalTables().isEmpty();
    TableGenerator tableGenerator = tableGeneratorFactory.getTableGenerator(
        md.getShardingAnno(), md.getGlobalTable(), isSqlUseGlobalTable, context);

    // ????????????????????????
    DataSourceType dataSourceType = getDataSourceType(operatorType, md);
    DataSourceGenerator dataSourceGenerator = dataSourceGeneratorFactory.
        getDataSourceGenerator(dataSourceType, md.getShardingAnno(), md.getDataSourceFactoryName(), context);

    AbstractOperator operator;
    switch (operatorType) {
      case QUERY:
        InvocationPageHandler invocationPageHandler = new InvocationPageHandler(pageHandler, pds);
        operator = new QueryOperator(rootNode, md, invocationPageHandler, config);
        break;
      case UPDATE:
        operator = new UpdateOperator(rootNode, md, config);
        break;
      case BATCHUPDATE:
        operator = new BatchUpdateOperator(rootNode, md, config);
        break;
      default:
        throw new IllegalStateException();
    }

    operator.setTableGenerator(tableGenerator);
    operator.setDataSourceGenerator(dataSourceGenerator);
    operator.setInvocationContextFactory(InvocationContextFactory.create(context));
    operator.setJdbcOperations(jdbcOperations);
    return operator;
  }

  OperatorType getOperatorType(List<ParameterDescriptor> pds, ASTRootNode rootNode) {
    OperatorType operatorType;
    if (rootNode.getSQLType() == SQLType.SELECT) {
      operatorType = OperatorType.QUERY;
    } else {
      operatorType = OperatorType.UPDATE;
      if (pds.size() == 1) { // ??????????????????
        ParameterDescriptor pd = pds.get(0);
        if (pd.canIterable() && rootNode.getJDBCIterableParameters().isEmpty()) {
          // ????????????????????????sql?????????in??????
          operatorType = OperatorType.BATCHUPDATE;
        }
      }
    }
    return operatorType;
  }

  DataSourceType getDataSourceType(OperatorType operatorType, MethodDescriptor md) {
    DataSourceType dataSourceType = DataSourceType.SLAVE;
    if (operatorType != OperatorType.QUERY || md.isAnnotationPresent(UseMaster.class)) {
      dataSourceType = DataSourceType.MASTER;
    }
    return dataSourceType;
  }

}
