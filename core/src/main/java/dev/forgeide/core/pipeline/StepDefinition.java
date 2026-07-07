package dev.forgeide.core.pipeline;

import java.util.List;

public sealed interface StepDefinition
        permits AgentStep, ScriptStep, JudgeStep, GateStep, BranchStep, PerTaskLoop, OutwardStep {

    String id();

    List<String> dependsOn();
}
