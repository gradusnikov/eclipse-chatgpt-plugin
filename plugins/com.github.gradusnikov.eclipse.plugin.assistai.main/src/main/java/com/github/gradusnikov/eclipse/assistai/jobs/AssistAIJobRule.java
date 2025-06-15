package com.github.gradusnikov.eclipse.assistai.jobs;

import org.eclipse.core.runtime.jobs.ISchedulingRule;

// Create a shared rule class
public class AssistAIJobRule implements ISchedulingRule {
	
    @Override
    public boolean contains(ISchedulingRule rule)
    {
        return rule == this;
    }
    
    @Override
    public boolean isConflicting(ISchedulingRule rule) 
    {
        return rule == this;
    }
}