package com.takesome.springsuite.module;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PublisherManagementCommand implements SuiteCommand {
    private final SuitePublisherManagementService service;

    public PublisherManagementCommand(SuitePublisherManagementService service) {
        this.service = service;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "publishers",
                List.of("publisher", "pubs"),
                "modules",
                "Manage module publisher trust store and module artifacts.",
                "Lists, fingerprints, trusts, revokes and deploys module publisher artifacts.",
                "publishers <list|fingerprint|trust-cert|trust-publisher|block-cert|revoke|deploy|build|sign> ...",
                CommandRiskLevel.LOCAL_MUTATION
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String action = invocation.arg(0).isBlank() ? "list" : invocation.arg(0).trim().toLowerCase();
        try {
            return switch (action) {
                case "list", "ls" -> CommandExecutionResult.ok("publishers: " + service.listPublishers().size(), Map.of("publishers", service.listPublishers()));
                case "fingerprint", "fp" -> fingerprint(invocation);
                case "trust-cert" -> trustCert(invocation);
                case "trust-publisher" -> trustPublisher(invocation);
                case "block-cert" -> blockCert(invocation);
                case "revoke" -> revoke(invocation);
                case "deploy" -> deploy(invocation);
                case "build" -> build(invocation);
                case "sign" -> sign(invocation);
                default -> CommandExecutionResult.failed("bad_publishers_action", "Unknown publishers action: " + action);
            };
        } catch (Exception ex) {
            return CommandExecutionResult.failed("publisher_command_failed", ex.getMessage());
        }
    }

    private CommandExecutionResult fingerprint(CommandInvocation invocation) {
        String path = invocation.arg(1);
        if (path.isBlank()) {
            return CommandExecutionResult.failed("missing_path", "usage: publishers fingerprint <jar>");
        }
        return CommandExecutionResult.ok("jar fingerprint", Map.of("fingerprint", service.fingerprint(new PathRequest(path))));
    }

    private CommandExecutionResult trustCert(CommandInvocation invocation) {
        String jarOrCert = invocation.arg(1);
        if (jarOrCert.isBlank()) {
            return CommandExecutionResult.failed("missing_cert", "usage: publishers trust-cert <jar|certificateSha256> [id] [name]");
        }
        PublisherMutationRequest request = jarOrCert.toLowerCase().endsWith(".jar")
                ? new PublisherMutationRequest(invocation.arg(2), invocation.arg(3), "", "", jarOrCert, "2099-12-31", false)
                : new PublisherMutationRequest(invocation.arg(2), invocation.arg(3), jarOrCert, "", "", "2099-12-31", false);
        return CommandExecutionResult.ok("certificate trusted", Map.of("publisher", service.trustCertificate(request)));
    }

    private CommandExecutionResult trustPublisher(CommandInvocation invocation) {
        String publisher = invocation.arg(1);
        if (publisher.isBlank()) {
            return CommandExecutionResult.failed("missing_publisher", "usage: publishers trust-publisher <publisherIdentity> [id] [name]");
        }
        PublisherMutationRequest request = new PublisherMutationRequest(invocation.arg(2), invocation.arg(3), "", publisher, "", "2099-12-31", false);
        return CommandExecutionResult.ok("publisher trusted", Map.of("publisher", service.trustPublisher(request)));
    }

    private CommandExecutionResult blockCert(CommandInvocation invocation) {
        String jarOrCert = invocation.arg(1);
        if (jarOrCert.isBlank()) {
            return CommandExecutionResult.failed("missing_cert", "usage: publishers block-cert <jar|certificateSha256> [id] [name]");
        }
        PublisherMutationRequest request = jarOrCert.toLowerCase().endsWith(".jar")
                ? new PublisherMutationRequest(invocation.arg(2), invocation.arg(3), "", "", jarOrCert, "", true)
                : new PublisherMutationRequest(invocation.arg(2), invocation.arg(3), jarOrCert, "", "", "", true);
        return CommandExecutionResult.ok("certificate blocked", Map.of("publisher", service.blockCertificate(request)));
    }

    private CommandExecutionResult revoke(CommandInvocation invocation) {
        String id = invocation.arg(1);
        if (id.isBlank()) {
            return CommandExecutionResult.failed("missing_id", "usage: publishers revoke <id|certificateSha256|publisherIdentity>");
        }
        return CommandExecutionResult.ok("publisher revoked", Map.of("publisher", service.revoke(new PublisherMutationRequest(id, "", id, id, "", "", true))));
    }

    private CommandExecutionResult deploy(CommandInvocation invocation) {
        String jar = invocation.arg(1);
        if (jar.isBlank()) {
            return CommandExecutionResult.failed("missing_jar", "usage: publishers deploy <jar> [targetFileName]");
        }
        return CommandExecutionResult.ok("module deploy requested", Map.of("result", service.deploy(new ModuleDeployRequest(jar, invocation.arg(2), true))));
    }
    private CommandExecutionResult build(CommandInvocation invocation) {
        if (invocation.args().size() < 3) {
            return CommandExecutionResult.failed("bad_build_usage", "usage: publishers build <cwd> <command> [args...]");
        }
        return CommandExecutionResult.ok("module build requested", Map.of("result", service.build(new ModuleBuildRequest(invocation.arg(1), invocation.args().subList(2, invocation.args().size()), 600))));
    }

    private CommandExecutionResult sign(CommandInvocation invocation) {
        if (invocation.args().size() < 6) {
            return CommandExecutionResult.failed("bad_sign_usage", "usage: publishers sign <jar> <outputJar> <keystore> <alias> <storeEnv> [keyEnv]");
        }
        return CommandExecutionResult.ok("module sign requested", Map.of("result", service.sign(new ModuleSignRequest(invocation.arg(1), invocation.arg(2), invocation.arg(3), invocation.arg(4), invocation.arg(5), invocation.arg(6)))));
    }

}
