/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ranger.authorization.presto.authorizer;

import com.google.common.collect.ImmutableSet;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.AccessDeniedException;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.Privilege;
import io.prestosql.spi.security.SystemAccessControl;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Locale.ENGLISH;

public class RangerSystemAccessControl
  implements SystemAccessControl {

  public static String RANGER_CONFIG_KEYTAB = "ranger.keytab";
  public static String RANGER_CONFIG_PRINCIPAL = "ranger.principal";

  public static String RANGER_PRESTO_SERVICETYPE = "presto";
  public static String RANGER_PRESTO_APPID = "presto";

  private static Logger LOG = LoggerFactory.getLogger(RangerSystemAccessControl.class);

  private RangerBasePlugin rangerPlugin;

  public RangerSystemAccessControl(Map<String, String> config) {
    super();

    if (config.get(RANGER_CONFIG_KEYTAB) != null && config.get(RANGER_CONFIG_PRINCIPAL) != null) {
      String keytab = config.get(RANGER_CONFIG_KEYTAB);
      String principal = config.get(RANGER_CONFIG_PRINCIPAL);

      LOG.info("Performing kerberos login with principal " + principal + " and keytab " + keytab);

      try {
        UserGroupInformation.setConfiguration(new Configuration());
        UserGroupInformation.loginUserFromKeytab(principal, keytab);
      } catch (IOException ioe) {
        LOG.error("Kerberos login failed", ioe);
        throw new RuntimeException(ioe);
      }
    }
    rangerPlugin = new RangerBasePlugin(RANGER_PRESTO_SERVICETYPE, RANGER_PRESTO_APPID);
    rangerPlugin.init();
    rangerPlugin.setResultProcessor(new RangerDefaultAuditHandler());
  }

  private boolean checkPermission(RangerPrestoResource resource, Identity identity, PrestoAccessType accessType) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("==> RangerSystemAccessControl.checkPermission(resource = " + resource + ", identity = " + identity +
              ", accessType = "  + accessType + ")");
    }
    boolean ret = false;
    UserGroupInformation ugi = UserGroupInformation.createRemoteUser(identity.getUser());
    String[] groups = ugi.getGroupNames();

    if (LOG.isDebugEnabled()) {
      LOG.debug("==> RangerSystemAccessControl.checkPermission(shortName=" + ugi.getShortUserName() +
                        ",group=" + Arrays.toString(groups) + ")");
    }

    Set<String> userGroups = null;
    if (groups != null && groups.length > 0) {
      userGroups = new HashSet<>(Arrays.asList(groups));
    }

    RangerPrestoAccessRequest request = new RangerPrestoAccessRequest(
      resource,
      ugi.getShortUserName(),
      userGroups,
      accessType
    );

    if (LOG.isDebugEnabled()) {
      LOG.debug("==> RangerSystemAccessControl.checkPermission(request = " + request + ")");
    }

    RangerAccessResult result = rangerPlugin.isAccessAllowed(request);
    if (result != null && result.getIsAllowed()) {
      ret = true;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("<== RangerSystemAccessControl.checkPermission(ret = " + ret + ")");
    }
    return ret;
  }

  @Override
  public void checkCanSetUser(Optional<Principal> principal, String userName) {
    if(LOG.isDebugEnabled()) {
      LOG.debug("==> RangerSystemAccessControl.checkCanSetUser(" + userName + ")");
    }

    /*
    if (!principal.isPresent()) {
      //AccessDeniedException.denySetUser(principal, userName);
    }*/

    //AccessDeniedException.denySetUser(principal, userName);
  }

  @Override
  public void checkCanSetSystemSessionProperty(Identity identity, String propertyName) {
    if (!checkPermission(new RangerPrestoResource(), identity, PrestoAccessType.ADMIN)) {
      LOG.info("==> RangerSystemAccessControl.checkCanSetSystemSessionProperty denied");
      AccessDeniedException.denySetSystemSessionProperty(propertyName);
    }
  }

  @Override
  public void checkCanAccessCatalog(Identity identity, String catalogName) {
    if (!checkPermission(createResource(catalogName), identity, PrestoAccessType.SELECT)) {
      LOG.info("==> RangerSystemAccessControl.checkCanAccessCatalog(" + catalogName + ") denied");
      AccessDeniedException.denyCatalogAccess(catalogName);
    }
  }

  @Override
  public Set<String> filterCatalogs(Identity identity, Set<String> catalogs) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("==> RangerSystemAccessControl.filterCatalogs(" + catalogs.toString() + ")");
    }
    ImmutableSet.Builder<String> filteredCatalogs = ImmutableSet.builder();
    for (String catalog : catalogs) {
      if (checkPermission(createResource(catalog), identity, PrestoAccessType.SELECT)) {
        filteredCatalogs.add(catalog);
      }
    }
    return filteredCatalogs.build();
  }

  @Override
  public void checkCanCreateSchema(Identity identity, CatalogSchemaName schema) {
    if (!checkPermission(createResource(schema.getCatalogName(), schema.getSchemaName()), identity, PrestoAccessType.CREATE)) {
      LOG.info("==> RangerSystemAccessControl.checkCanCreateSchema(" + schema.getSchemaName() + ") denied");
      AccessDeniedException.denyCreateSchema(schema.getSchemaName());
    }
  }

  @Override
  public void checkCanDropSchema(Identity identity, CatalogSchemaName schema) {
    if (!checkPermission(createResource(schema.getCatalogName(), schema.getSchemaName()), identity, PrestoAccessType.DROP)) {
      LOG.info("==> RangerSystemAccessControl.checkCanDropSchema(" + schema.getSchemaName() + ") denied");
      AccessDeniedException.denyDropSchema(schema.getSchemaName());
    }
  }

  @Override
  public void checkCanRenameSchema(Identity identity, CatalogSchemaName schema, String newSchemaName) {
    RangerPrestoResource res = createResource(schema.getCatalogName(), schema.getSchemaName());
    if (!checkPermission(res, identity, PrestoAccessType.ALTER)) {
      LOG.info("==> RangerSystemAccessControl.checkCanRenameSchema(" + schema.getSchemaName() + ") denied");
      AccessDeniedException.denyRenameSchema(schema.getSchemaName(), newSchemaName);
    }
  }

  @Override
  public void checkCanShowSchemas(Identity identity, String catalogName) {
    if (!checkPermission(createResource(catalogName), identity, PrestoAccessType.SELECT)) {
      LOG.info("==> RangerSystemAccessControl.checkCanShowSchemas(" + catalogName + ") denied");
      AccessDeniedException.denyShowSchemas(catalogName);
    }
  }

  @Override
  public Set<String> filterSchemas(Identity identity, String catalogName, Set<String> schemaNames) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("==> RangerSystemAccessControl.filterSchemas(" + catalogName + ")");
    }
    ImmutableSet.Builder<String> filteredSchemas = ImmutableSet.builder();
    for (String schema : schemaNames) {
      if (checkPermission(createResource(catalogName, schema), identity, PrestoAccessType.SELECT)) {
        filteredSchemas.add(schema);
      }
    }
    return filteredSchemas.build();
  }

  @Override
  public void checkCanCreateTable(Identity identity, CatalogSchemaTableName table) {
    if (!checkPermission(createResource(table), identity, PrestoAccessType.CREATE)) {
      LOG.info("==> RangerSystemAccessControl.checkCanCreateTable(" + table.getSchemaTableName().getTableName() + ") denied");
      AccessDeniedException.denyCreateTable(table.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanDropTable(Identity identity, CatalogSchemaTableName table) {
    if (!checkPermission(createResource(table), identity, PrestoAccessType.DROP)) {
      LOG.info("==> RangerSystemAccessControl.checkCanDropTable(" + table.getSchemaTableName().getTableName() + ") denied");
      AccessDeniedException.denyDropTable(table.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanRenameTable(Identity identity, CatalogSchemaTableName table, CatalogSchemaTableName newTable) {
    RangerPrestoResource res = createResource(table);
    if (!checkPermission(res, identity, PrestoAccessType.ALTER)) {
      LOG.info("==> RangerSystemAccessControl.checkCanRenameTable(" + table.getSchemaTableName().getTableName() + ") denied");
      AccessDeniedException.denyRenameTable(table.getSchemaTableName().getTableName(), newTable.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanShowTablesMetadata(Identity identity, CatalogSchemaName schema) {
    if (!checkPermission(createResource(schema.getCatalogName(), schema.getSchemaName()), identity, PrestoAccessType.SELECT)) {
      LOG.info("==> RangerSystemAccessControl.checkCanShowTablesMetadata(" + schema.getSchemaName() + ") denied");
      AccessDeniedException.denyShowTablesMetadata(schema.getSchemaName());
    }
  }

  @Override
  public Set<SchemaTableName> filterTables(Identity identity, String catalogName, Set<SchemaTableName> tableNames) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("==> RangerSystemAccessControl.filterTables(" + catalogName + ")");
    }
    ImmutableSet.Builder<SchemaTableName> filteredTables = ImmutableSet.builder();
    for (SchemaTableName tableName : tableNames) {
      if (checkPermission(createResource(catalogName, tableName.getSchemaName(), tableName.getTableName()),
                          identity, PrestoAccessType.SELECT)) {
        filteredTables.add(tableName);
      }
    }
    return filteredTables.build();
  }

  @Override
  public void checkCanAddColumn(Identity identity, CatalogSchemaTableName table) {
    RangerPrestoResource res = createResource(table);
    if (!checkPermission(res, identity, PrestoAccessType.ALTER)) {
      LOG.info("==> RangerSystemAccessControl.checkCanAddColumn(" + table.getSchemaTableName().getTableName() + ") deny");
      AccessDeniedException.denyAddColumn(table.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanDropColumn(Identity identity, CatalogSchemaTableName table) {
    RangerPrestoResource res = createResource(table);
    if (!checkPermission(res, identity, PrestoAccessType.ALTER)) {
      LOG.info("==> RangerSystemAccessControl.checkCanDropColumn(" + table.getSchemaTableName().getTableName() + ") denied");
      AccessDeniedException.denyDropColumn(table.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanRenameColumn(Identity identity, CatalogSchemaTableName table) {
    RangerPrestoResource res = createResource(table);
    if (!checkPermission(res, identity, PrestoAccessType.ALTER)) {
      LOG.info("==> RangerSystemAccessControl.checkCanRenameColumn(" + table.getSchemaTableName().getTableName() + ") denied");
      AccessDeniedException.denyRenameColumn(table.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanSelectFromColumns(Identity identity, CatalogSchemaTableName table, Set<String> columns) {
    for (RangerPrestoResource res : createResource(table, columns)) {
      if (!checkPermission(res, identity, PrestoAccessType.SELECT)) {
        LOG.info("==> RangerSystemAccessControl.checkCanSelectFromColumns(" + table.getSchemaTableName().getTableName() + ") denied");
        AccessDeniedException.denySelectColumns(table.getSchemaTableName().getTableName(), columns);
      }
    }
  }

  @Override
  public void checkCanInsertIntoTable(Identity identity, CatalogSchemaTableName table) {
    RangerPrestoResource res = createResource(table);
    if (!checkPermission(res, identity, PrestoAccessType.INSERT)) {
      LOG.info("==> RangerSystemAccessControl.checkCanInsertIntoTable(" + table.getSchemaTableName().getTableName() + ") denied");
      AccessDeniedException.denyInsertTable(table.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanDeleteFromTable(Identity identity, CatalogSchemaTableName table) {
    if (!checkPermission(createResource(table), identity, PrestoAccessType.DELETE)) {
      LOG.info("==> RangerSystemAccessControl.checkCanDeleteFromTable(" + table.getSchemaTableName().getTableName() + ") denied");
      AccessDeniedException.denyDeleteTable(table.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanCreateView(Identity identity, CatalogSchemaTableName view) {
    if (!checkPermission(createResource(view), identity, PrestoAccessType.CREATE)) {
      LOG.info("==> RangerSystemAccessControl.checkCanCreateView(" + view.getSchemaTableName().getTableName() + ") denied");
      AccessDeniedException.denyCreateView(view.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanDropView(Identity identity, CatalogSchemaTableName view) {
    if (!checkPermission(createResource(view), identity, PrestoAccessType.DROP)) {
      LOG.info("==> RangerSystemAccessControl.checkCanDropView(" + view.getSchemaTableName().getTableName() + ") denied");
      AccessDeniedException.denyCreateView(view.getSchemaTableName().getTableName());
    }
  }

  @Override
  public void checkCanCreateViewWithSelectFromColumns(Identity identity, CatalogSchemaTableName table, Set<String> columns) {
    for (RangerPrestoResource res : createResource(table, columns)) {
      if (!checkPermission(res, identity, PrestoAccessType.CREATE)) {
        LOG.info("==> RangerSystemAccessControl.checkCanDropView(" + table.getSchemaTableName().getTableName() + ") denied");
        AccessDeniedException.denyCreateViewWithSelect(table.getSchemaTableName().getTableName(), identity);
      }
    }
  }

  @Override
  public void checkCanSetCatalogSessionProperty(Identity identity, String catalogName, String propertyName) {
    if (!checkPermission(createResource(catalogName), identity, PrestoAccessType.ADMIN)) {
      LOG.info("==> RangerSystemAccessControl.checkCanSetSystemSessionProperty(" + catalogName + ") denied");
      AccessDeniedException.denySetCatalogSessionProperty(catalogName, propertyName);
    }
  }

  @Override
  public void checkCanGrantTablePrivilege(Identity identity, Privilege privilege, CatalogSchemaTableName table, PrestoPrincipal grantee, boolean withGrantOption) {
    if (!checkPermission(createResource(table), identity, PrestoAccessType.ADMIN)) {
      LOG.info("==> RangerSystemAccessControl.checkCanGrantTablePrivilege(" + table + ") denied");
      AccessDeniedException.denyGrantTablePrivilege(privilege.toString(), table.toString());
    }
  }

  @Override
  public void checkCanRevokeTablePrivilege(Identity identity, Privilege privilege, CatalogSchemaTableName table, PrestoPrincipal revokee, boolean grantOptionFor) {
    if (!checkPermission(createResource(table), identity, PrestoAccessType.ADMIN)) {
      LOG.info("==> RangerSystemAccessControl.checkCanRevokeTablePrivilege(" + table + ") denied");
      AccessDeniedException.denyRevokeTablePrivilege(privilege.toString(), table.toString());
    }
  }

  @Override
  public void checkCanShowRoles(Identity identity, String catalogName) {
    if (!checkPermission(createResource(catalogName), identity, PrestoAccessType.ADMIN)) {
      LOG.info("==> RangerSystemAccessControl.checkCanShowRoles(" + catalogName + ") denied");
      AccessDeniedException.denyShowRoles(catalogName);
    }
  }

  @Override
  public void checkCanShowColumnsMetadata(Identity identity, CatalogSchemaTableName table) {
    LOG.info("==> RangerSystemAccessControl.checkCanShowColumnsMetadata(" + table + ")");
  }

  @Override
  public List<ColumnMetadata> filterColumns(Identity identity, CatalogSchemaTableName table, List<ColumnMetadata> columns) {
    LOG.info("==> RangerSystemAccessControl.checkCanShowColumnsMetadata(table=" + table + ",columns=" + columns.toString() + ")");
    return columns;
  }

  private static RangerPrestoResource createResource(CatalogSchemaName catalogSchemaName) {
    return createResource(catalogSchemaName.getCatalogName(), catalogSchemaName.getSchemaName());
  }

  private static RangerPrestoResource createResource(CatalogSchemaTableName catalogSchemaTableName) {
    return createResource(catalogSchemaTableName.getCatalogName(),
      catalogSchemaTableName.getSchemaTableName().getSchemaName(),
      catalogSchemaTableName.getSchemaTableName().getTableName());
  }

  private static RangerPrestoResource createResource(String catalogName) {
    return new RangerPrestoResource(catalogName, Optional.empty(), Optional.empty());
  }

  private static RangerPrestoResource createResource(String catalogName, String schemaName) {
    return new RangerPrestoResource(catalogName, Optional.of(schemaName), Optional.empty());
  }

  private static RangerPrestoResource createResource(String catalogName, String schemaName, final String tableName) {
    return new RangerPrestoResource(catalogName, Optional.of(schemaName), Optional.of(tableName));
  }

  private static RangerPrestoResource createResource(String catalogName, String schemaName, final String tableName, final Optional<String> column) {
    return new RangerPrestoResource(catalogName, Optional.of(schemaName), Optional.of(tableName), column);
  }

  private static List<RangerPrestoResource> createResource(CatalogSchemaTableName table, Set<String> columns) {
    List<RangerPrestoResource> colRequests = new ArrayList<>();

    if (columns.size() > 0) {
      for (String column : columns) {
        RangerPrestoResource rangerPrestoResource = createResource(table.getCatalogName(),
          table.getSchemaTableName().getSchemaName(),
          table.getSchemaTableName().getTableName(), Optional.of(column));
        colRequests.add(rangerPrestoResource);
      }
    } else {
      colRequests.add(createResource(table.getCatalogName(),
        table.getSchemaTableName().getSchemaName(),
        table.getSchemaTableName().getTableName(), Optional.empty()));
    }
    return colRequests;
  }
}

class RangerPrestoResource
  extends RangerAccessResourceImpl {
  private static final String KEY_DEFAULT_VALUE = "-";

  public static final String KEY_CATALOG = "catalog";
  public static final String KEY_SCHEMA = "schema";
  public static final String KEY_TABLE = "table";
  public static final String KEY_COLUMN = "column";

  public RangerPrestoResource() {}

  public RangerPrestoResource(String catalogName, Optional<String> schema, Optional<String> table) {
    setValue(KEY_CATALOG, catalogName);
    setValue(KEY_SCHEMA, schema.orElse(KEY_DEFAULT_VALUE));
    setValue(KEY_TABLE, table.orElse(KEY_DEFAULT_VALUE));
    setValue(KEY_COLUMN, KEY_DEFAULT_VALUE);
  }

  public RangerPrestoResource(String catalogName, Optional<String> schema, Optional<String> table, Optional<String> column) {
    setValue(KEY_CATALOG, catalogName);
    setValue(KEY_SCHEMA, schema.orElse(KEY_DEFAULT_VALUE));
    setValue(KEY_TABLE, table.orElse(KEY_DEFAULT_VALUE));
    setValue(KEY_COLUMN, column.orElse(KEY_DEFAULT_VALUE));
  }

  public String getCatalogName() {
    return (String) getValue(KEY_CATALOG);
  }

  public String getTable() {
    return (String) getValue(KEY_TABLE);
  }

  public String getCatalog() {
    return (String) getValue(KEY_CATALOG);
  }

  public String getSchema() { return (String) getValue(KEY_SCHEMA); }

  public Optional<SchemaTableName> getSchemaTable() {
    final String schema = getSchema();
    if (StringUtils.isNotEmpty(schema)) {
      return Optional.of(new SchemaTableName(schema, Optional.ofNullable(getTable()).orElse("*")));
    }
    return Optional.empty();
  }
}

class RangerPrestoAccessRequest
  extends RangerAccessRequestImpl {
  public RangerPrestoAccessRequest(RangerPrestoResource resource,
                                   String user,
                                   Set<String> userGroups,
                                   PrestoAccessType prestoAccessType)

  {
    super(resource,
      prestoAccessType == PrestoAccessType.USE ? RangerPolicyEngine.ANY_ACCESS :
        prestoAccessType == PrestoAccessType.ADMIN ? RangerPolicyEngine.ADMIN_ACCESS :
          prestoAccessType.name().toLowerCase(ENGLISH), user,
      userGroups);
  }
}

enum PrestoAccessType {
  CREATE, DROP, SELECT, INSERT, DELETE, USE, ALTER, ALL, ADMIN;
}