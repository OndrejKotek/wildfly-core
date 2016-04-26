/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014, JBoss Inc., and individual contributors as indicated
 * by the @authors tag.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.as.test.manualmode.auditlog;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.AUTHENTICATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CORE_SERVICE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.LOCAL;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SECURITY_REALM;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.inject.Inject;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.domain.management.CoreManagementResourceDefinition;
import org.jboss.as.domain.management.audit.AccessAuditResourceDefinition;
import org.jboss.as.domain.management.audit.AuditLogLoggerResourceDefinition;
import org.jboss.as.test.categories.CommonCriteria;
import org.jboss.as.test.integration.auditlog.AuditLogToSyslogSetup;
import org.jboss.as.test.integration.auditlog.AuditLogToUDPSyslogSetup;
import org.jboss.as.test.integration.security.common.CoreUtils;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.as.test.syslogserver.BlockedSyslogServerEventHandler;
import org.jboss.as.test.syslogserver.Rfc5424SyslogEvent;
import org.jboss.dmr.ModelNode;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.productivity.java.syslog4j.server.SyslogServerEventIF;
import org.wildfly.core.testrunner.ServerControl;
import org.wildfly.core.testrunner.ServerController;
import org.wildfly.core.testrunner.WildflyTestRunner;
import org.xnio.IoUtils;

/**
 * Tests that fields of Audit log have right content.
 *
 * @author Ondrej Lukas
 * @author Josef Cacek
 */
@RunWith(WildflyTestRunner.class)
@ServerControl(manual = true)
@Category(CommonCriteria.class)
public class AuditLogFieldsOfLogTestCase {

    private final BlockingQueue<SyslogServerEventIF> queue = BlockedSyslogServerEventHandler.getQueue();

    private static final AuditLogToUDPSyslogSetup SYSLOG_SETUP = new AuditLogToUDPSyslogSetup();
    private final Pattern DATE_STAMP_PATTERN = Pattern.compile("\\d\\d\\d\\d-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d - \\{");

    @Inject
    private ServerController container;

    private File file;

    private PathAddress auditLogConfigAddress;
    private PathAddress mgmtRealmConfigAddress;
    private PathAddress syslogHandlerAddress;
    private PathAddress addSyslogHandler;
    private static final int ADJUSTED_SECOND = TimeoutUtil.adjust(1000);

    /**
     * @test.objective Test whether fields in Audit Log have right content
     * @test.expectedResult All asserts are correct and test finishes without any exception.
     */
    @Test
    public void testAuditLoggingFields() throws Exception {
        container.start();
        if (file.exists()) {
            file.delete();
        }

        queue.clear();
        SyslogServerEventIF syslogEvent = null;

        Assert.assertTrue(makeOneLog());
        syslogEvent = queue.poll(5 * ADJUSTED_SECOND, TimeUnit.MILLISECONDS);
        Assert.assertNotNull("Event wasn't logged into the syslog", syslogEvent);

        Rfc5424SyslogEvent event = (Rfc5424SyslogEvent) syslogEvent;
        String message = event.getMessage();
        Assert.assertNotNull("Message in the syslog event is empty", message);
        message = DATE_STAMP_PATTERN.matcher(message).replaceFirst("{");
        ModelNode syslogNode = ModelNode.fromJSONString(message);
        checkLog("Syslog", syslogNode);
        List<ModelNode> logs = readFile(file, 1);
        ModelNode log = logs.get(0);
        checkLog("File", log);
    }

    private void checkLog(String handler, ModelNode log) {
        final String failMsg = "Unexpected value in " + handler;
        Assert.assertEquals(failMsg, "core", log.get("type").asString());
        Assert.assertEquals(failMsg, "false", log.get("r/o").asString());
        Assert.assertEquals(failMsg, "false", log.get("booting").asString());
        Assert.assertTrue(failMsg, log.get("version").isDefined());
        Assert.assertEquals(failMsg, "IAmAdmin", log.get("user").asString());
        Assert.assertFalse(failMsg, log.get("domainUUID").isDefined());
        Assert.assertEquals(failMsg, "NATIVE", log.get("access").asString());
        Assert.assertTrue(failMsg, log.get("remote-address").isDefined());
        Assert.assertEquals(failMsg, "true", log.get("success").asString());
        List<ModelNode> operations = log.get("ops").asList();
        Assert.assertEquals(failMsg, 1, operations.size());
    }

    private boolean makeOneLog() throws IOException {
        ModelNode op = Util.getWriteAttributeOperation(auditLogConfigAddress,
                AuditLogLoggerResourceDefinition.LOG_BOOT.getName(), new ModelNode(true));
        ModelNode result = container.getClient().getControllerClient().execute(op);
        return SUCCESS.equals(result.get(OUTCOME).asString());
    }

    @Before
    public void beforeTest() throws Exception {
        file = new File(System.getProperty("jboss.home"));
        file = new File(file, "standalone");
        file = new File(file, "data");
        file = new File(file, "audit-log.log");
        if (file.exists()) {
            file.delete();
        }

        container.start();
        final ModelControllerClient client = container.getClient().getControllerClient();

        ModelNode op;
        ModelNode result;

        mgmtRealmConfigAddress = PathAddress.pathAddress(PathElement.pathElement(CORE_SERVICE, MANAGEMENT),
                PathElement.pathElement(SECURITY_REALM, "ManagementRealm"), PathElement.pathElement(AUTHENTICATION, LOCAL));
        op = Util.getWriteAttributeOperation(mgmtRealmConfigAddress, "default-user", new ModelNode("IAmAdmin"));
        result = client.execute(op);

        auditLogConfigAddress = PathAddress.pathAddress(CoreManagementResourceDefinition.PATH_ELEMENT,
                AccessAuditResourceDefinition.PATH_ELEMENT, AuditLogLoggerResourceDefinition.PATH_ELEMENT);

        op = Util.getWriteAttributeOperation(auditLogConfigAddress, AuditLogLoggerResourceDefinition.ENABLED.getName(),
                new ModelNode(true));
        result = client.execute(op);
        Assert.assertEquals(result.get("failure-description").asString(), SUCCESS, result.get(OUTCOME).asString());

        SYSLOG_SETUP.setup(container.getClient());

        op = Util.getWriteAttributeOperation(AuditLogToSyslogSetup.AUDIT_LOG_LOGGER_ADDR, ENABLED, true);
        CoreUtils.applyUpdate(op, client);

        container.stop();
    }

    @After
    public void afterTest() throws Exception {
        SYSLOG_SETUP.tearDown(container.getClient());
        final ModelControllerClient client = container.getClient().getControllerClient();

        ModelNode op = Util.getWriteAttributeOperation(auditLogConfigAddress,
                AuditLogLoggerResourceDefinition.ENABLED.getName(), new ModelNode(false));
        client.execute(op);
        op = Util.getWriteAttributeOperation(mgmtRealmConfigAddress, "default-user", new ModelNode("$local"));
        client.execute(op);

        op = Util.getResourceRemoveOperation(addSyslogHandler);
        client.execute(op);
        op = Util.getResourceRemoveOperation(syslogHandlerAddress);
        client.execute(op);

        if (file.exists()) {
            file.delete();
        }
        try {
            // Stop the container
            container.stop();
        } finally {
            IoUtils.safeClose(client);
        }
    }

    protected List<ModelNode> readFile(File file, int expectedRecords) throws IOException {
        List<ModelNode> list = new ArrayList<>();
        final BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
        try {
            StringWriter writer = null;
            String line = reader.readLine();
            while (line != null) {
                if (DATE_STAMP_PATTERN.matcher(line).find()) {
                    if (writer != null) {
                        list.add(ModelNode.fromJSONString(writer.getBuffer().toString()));
                    }
                    writer = new StringWriter();
                    writer.append("{");
                } else {
                    if (writer != null) { writer.append("\n" + line); }
                }
                line = reader.readLine();
            }
            if (writer != null) {
                list.add(ModelNode.fromJSONString(writer.getBuffer().toString()));
            }
        } finally {
            IoUtils.safeClose(reader);
        }
        Assert.assertEquals(list.toString(), expectedRecords, list.size());
        return list;
    }

}
