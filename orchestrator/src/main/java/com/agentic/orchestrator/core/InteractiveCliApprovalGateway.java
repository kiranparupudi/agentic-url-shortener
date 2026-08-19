package com.agentic.orchestrator.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Pauses and waits for a real y/n on stdin. If stdin isn't attached, denies instead of hanging. */
public final class InteractiveCliApprovalGateway implements ApprovalGateway {

    private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

    @Override
    public ApprovalDecision requestApproval(String stageId, String summary, ExecutionContext ctx) {
        System.out.println();
        System.out.println("================ HUMAN APPROVAL REQUIRED ================");
        System.out.println("Stage      : " + stageId);
        System.out.println("Run        : " + ctx.runId() + " (" + ctx.scenarioName() + ")");
        System.out.println("Summary    : " + summary);
        System.out.print("Approve? [y/n]: ");
        System.out.flush();
        try {
            String line = reader.readLine();
            if (line == null) {
                System.out.println("(no stdin attached - treating as DENY / safe-stop)");
                return new ApprovalDecision(false, "system", "no stdin available, defaulted to deny");
            }
            boolean approved = line.trim().equalsIgnoreCase("y") || line.trim().equalsIgnoreCase("yes");
            System.out.println(approved ? "-> APPROVED by operator" : "-> DENIED by operator");
            System.out.println("===========================================================");
            return new ApprovalDecision(approved, "operator", line.trim());
        } catch (IOException e) {
            return new ApprovalDecision(false, "system", "stdin read error: " + e.getMessage());
        }
    }
}
