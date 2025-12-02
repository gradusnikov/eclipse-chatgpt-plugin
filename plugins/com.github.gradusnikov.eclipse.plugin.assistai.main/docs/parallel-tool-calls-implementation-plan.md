# Parallel Tool Calls Implementation Plan

## Overview

This document outlines the strategy and implementation plan for handling parallel tool calls in the OpenAI Responses API client. Currently, the system hardcodes `parallel_tool_calls: false`, forcing sequential execution of function calls. This plan addresses the TODO on line 159 of `OpenAIResponsesJavaHttpClient.java`.

## Current State Analysis

### Existing Limitations
- **Hardcoded Sequential Mode**: `parallel_tool_calls: false` is hardcoded
- **Single Function State Machine**: `FunctionOutputState` handles one function call at a time
- **Sequential Response Processing**: Events processed one by one in `processResponseEvent`
- **Single Buffer Architecture**: `FunctionCallSubscriber` uses one JSON buffer
- **Single Job Execution**: `ExecuteFunctionCallJob` designed for individual function execution

### Technical Debt
- No user configuration for parallel vs sequential mode
- State machine cannot track multiple concurrent function calls
- Response coordination logic missing for parallel execution
- No error handling for partial parallel call failures

## Implementation Strategy

### Phase 1: Configuration Infrastructure 🟢 (Low Risk)

#### 1.1 Add Preference Constants
**File**: `src/com/github/gradusnikov/eclipse/assistai/preferences/PreferenceConstants.java`
```java
// Add new constants for parallel tool calls
public static final String ASSISTAI_ENABLE_PARALLEL_TOOL_CALLS = "AssistAIEnableParallelToolCalls";
public static final String ASSISTAI_MAX_PARALLEL_TOOL_CALLS = "AssistAIMaxParallelToolCalls";
```

#### 1.2 Update Preference Initializer
**File**: `src/com/github/gradusnikov/eclipse/assistai/preferences/PreferenceInitializer.java`
```java
// Set default values
store.setDefault(PreferenceConstants.ASSISTAI_ENABLE_PARALLEL_TOOL_CALLS, false);
store.setDefault(PreferenceConstants.ASSISTAI_MAX_PARALLEL_TOOL_CALLS, 3);
```

#### 1.3 Add Model-Specific Configuration
**File**: `src/com/github/gradusnikov/eclipse/assistai/preferences/models/ModelApiDescriptor.java`
- Add `parallelToolCallsSupported` field to model descriptor
- Update constructor and methods accordingly

#### 1.4 Update UI Preferences
**File**: `src/com/github/gradusnikov/eclipse/assistai/preferences/models/ModelPreferencePage.java`
- Add checkbox for enabling parallel tool calls
- Add spinner for max parallel calls limit
- Show/hide based on model capabilities

#### 1.5 Update Request Body Logic
**File**: `src/com/github/gradusnikov/eclipse/assistai/network/clients/OpenAIResponsesJavaHttpClient.java`
```java
// Replace hardcoded false with preference-based logic
boolean enableParallel = preferenceStore.getBoolean(PreferenceConstants.ASSISTAI_ENABLE_PARALLEL_TOOL_CALLS);
requestBody.put("parallel_tool_calls", enableParallel && model.parallelToolCallsSupported());
```

**Deliverables:**
- [ ] Updated preference constants
- [ ] Enhanced model descriptor
- [ ] UI preference controls
- [ ] Dynamic request configuration
- [ ] Unit tests for preference handling

---

### Phase 2: Enhanced State Machine 🟡 (Medium Risk)

#### 2.1 Redesign Function Output State
**File**: `src/com/github/gradusnikov/eclipse/assistai/network/clients/OpenAIResponsesJavaHttpClient.java`

Create new parallel-aware function state:
```java
private class ParallelFunctionOutputState implements State {
    private final Map<String, FunctionCallTracker> activeCalls = new ConcurrentHashMap<>();
    
    private static class FunctionCallTracker {
        String callId;
        String name;
        StringBuilder arguments = new StringBuilder();
        boolean completed = false;
    }
    
    @Override
    public State begin(JsonNode node) {
        String callId = node.get("call_id").asText();
        String name = node.get("name").asText();
        
        FunctionCallTracker tracker = new FunctionCallTracker();
        tracker.callId = callId;
        tracker.name = name;
        activeCalls.put(callId, tracker);
        
        // Emit function call start
        emitFunctionCallStart(callId, name);
        return this;
    }
    
    @Override
    public State update(JsonNode node) {
        String callId = extractCallId(node);
        FunctionCallTracker tracker = activeCalls.get(callId);
        
        if (tracker != null && node.has("delta")) {
            String argumentsDelta = node.get("delta").asText();
            tracker.arguments.append(argumentsDelta);
            emitFunctionCallArguments(callId, argumentsDelta);
        }
        return this;
    }
    
    @Override
    public State finish(JsonNode node) {
        String callId = extractCallId(node);
        FunctionCallTracker tracker = activeCalls.get(callId);
        
        if (tracker != null) {
            tracker.completed = true;
            emitFunctionCallComplete(callId);
            
            // Check if all calls completed
            if (allCallsCompleted()) {
                activeCalls.clear();
                return NULL_STATE;
            }
        }
        return this;
    }
}
```

#### 2.2 Update State Selection Logic
Modify `processResponseEvent` to choose between sequential and parallel states based on configuration.

#### 2.3 Add Call Coordination
Implement logic to track multiple concurrent calls and coordinate their completion.

**Deliverables:**
- [ ] Parallel function output state implementation
- [ ] Call tracking and coordination logic
- [ ] Updated state selection mechanism
- [ ] Integration tests for state machine

---

### Phase 3: Function Call Processing Enhancement 🔴 (High Risk)

#### 3.1 Redesign Function Call Subscriber
**File**: `src/com/github/gradusnikov/eclipse/assistai/network/subscribers/FunctionCallSubscriber.java`

```java
@Creatable
public class FunctionCallSubscriber implements Flow.Subscriber<Incoming> {
    private final Map<String, StringBuffer> jsonBuffers = new ConcurrentHashMap<>();
    private final Set<String> completedCalls = ConcurrentHashMap.newKeySet();
    
    @Override
    public void onNext(Incoming item) {
        if (Incoming.Type.FUNCTION_CALL == item.type()) {
            String payload = item.payload();
            String callId = extractCallId(payload);
            
            jsonBuffers.computeIfAbsent(callId, k -> new StringBuffer())
                      .append(payload);
                      
            if (isFunctionCallComplete(payload)) {
                completedCalls.add(callId);
                processFunctionCall(callId);
            }
        }
        subscription.request(1);
    }
    
    private void processFunctionCall(String callId) {
        StringBuffer buffer = jsonBuffers.get(callId);
        if (buffer != null) {
            // Process individual function call
            scheduleFunctionExecution(callId, buffer.toString());
            jsonBuffers.remove(callId);
        }
    }
}
```

#### 3.2 Enhance Function Call Tracking
- Add call ID extraction logic
- Implement completion detection per call
- Handle interleaved function call events

#### 3.3 Update Job Scheduling
Modify job scheduling to handle multiple concurrent executions:
```java
private void scheduleFunctionExecution(String callId, String json) {
    ExecuteFunctionCallJob job = executeFunctionCallJobProvider.get();
    job.setFunctionCall(parseFunctionCall(json));
    job.setCallId(callId);
    job.schedule();
}
```

**Deliverables:**
- [ ] Multi-buffer function call subscriber
- [ ] Call ID extraction and tracking
- [ ] Concurrent job scheduling
- [ ] Comprehensive unit tests

---

### Phase 4: Response Coordination 🔴 (High Risk)

#### 4.1 Implement Call Completion Tracking
**File**: `src/com/github/gradusnikov/eclipse/assistai/jobs/ExecuteFunctionCallJob.java`

```java
public class ExecuteFunctionCallJob extends Job {
    private static final Map<String, Set<String>> conversationCalls = new ConcurrentHashMap<>();
    private static final Object coordinationLock = new Object();
    
    @Override
    protected IStatus run(IProgressMonitor monitor) {
        try {
            // Execute function call
            Object result = executeFunction();
            
            // Store result
            storeCallResult(callId, result);
            
            // Check if all parallel calls completed
            synchronized (coordinationLock) {
                markCallCompleted(conversationId, callId);
                
                if (allCallsCompletedForConversation(conversationId)) {
                    // Send all results back to API
                    sendBatchedResults(conversationId);
                    cleanupConversation(conversationId);
                }
            }
            
            return Status.OK_STATUS;
        } catch (Exception e) {
            handleCallFailure(conversationId, callId, e);
            return Status.CANCEL_STATUS;
        }
    }
}
```

#### 4.2 Batch Result Handling
Implement logic to collect all parallel call results before sending response:
```java
private void sendBatchedResults(String conversationId) {
    List<Map<String, Object>> outputs = new ArrayList<>();
    
    for (String callId : getCompletedCalls(conversationId)) {
        Object result = getCallResult(callId);
        outputs.add(createFunctionCallOutput(callId, result));
    }
    
    // Send batched response
    sendToolOutputs(outputs);
}
```

#### 4.3 Error Handling Strategy
- **Partial Failures**: Handle cases where some parallel calls succeed and others fail
- **Timeout Management**: Implement timeouts for parallel call coordination
- **Retry Logic**: Add retry mechanisms for failed parallel calls
- **Graceful Degradation**: Fall back to sequential mode on coordination failures

#### 4.4 Conversation State Management
Ensure conversation state remains consistent during parallel execution:
- Track conversation context across parallel calls
- Handle context updates from multiple simultaneous calls
- Maintain message ordering in conversation history

**Deliverables:**
- [ ] Call completion coordination system
- [ ] Batched result processing
- [ ] Comprehensive error handling
- [ ] Conversation state management
- [ ] Integration tests for full workflow

---

## Implementation Timeline

### Sprint 1 (1-2 weeks): Foundation
- **Phase 1**: Configuration infrastructure
- **Outcome**: Users can enable/disable parallel calls via preferences

### Sprint 2 (2-3 weeks): Core Logic  
- **Phase 2**: Enhanced state machine
- **Outcome**: System can track multiple concurrent function calls

### Sprint 3 (3-4 weeks): Processing Pipeline
- **Phase 3**: Function call processing enhancement  
- **Outcome**: Multiple function calls can be processed simultaneously

### Sprint 4 (2-3 weeks): Coordination & Polish
- **Phase 4**: Response coordination
- **Outcome**: Complete parallel tool calls functionality with error handling

## Risk Assessment

### High Risk Areas
- **State Machine Complexity**: Managing multiple concurrent states
- **Race Conditions**: Coordinating parallel execution safely
- **Error Propagation**: Handling partial failures gracefully
- **Memory Management**: Tracking multiple concurrent operations

### Mitigation Strategies
- **Extensive Unit Testing**: Cover all state transitions and edge cases
- **Integration Testing**: Test with real OpenAI API calls
- **Feature Flags**: Allow runtime enable/disable of parallel mode
- **Monitoring**: Add logging and metrics for parallel call tracking
- **Gradual Rollout**: Start with limited parallel call count

## Success Criteria

### Functional Requirements
- [ ] Users can configure parallel vs sequential tool calls
- [ ] System correctly handles multiple concurrent function calls
- [ ] All parallel call results are properly coordinated
- [ ] Error handling works for partial failures
- [ ] Performance improves for scenarios with multiple tool calls

### Non-Functional Requirements  
- [ ] No regression in sequential mode performance
- [ ] Memory usage remains reasonable with parallel calls
- [ ] System remains stable under concurrent load
- [ ] Configuration changes take effect without restart
- [ ] Comprehensive logging for troubleshooting

## Testing Strategy

### Unit Tests
- Preference handling and configuration
- State machine transitions with multiple calls
- Function call parsing and tracking
- Result coordination logic

### Integration Tests
- End-to-end parallel tool call scenarios
- Error handling with partial failures
- Performance comparison: sequential vs parallel
- Real API integration testing

### Performance Tests
- Memory usage with multiple concurrent calls
- Response time improvements
- Concurrent execution limits
- Resource cleanup verification

## Documentation Updates

### User Documentation
- [ ] Update user guide with parallel tool calls configuration
- [ ] Add troubleshooting section for parallel call issues
- [ ] Document performance implications and recommendations

### Developer Documentation  
- [ ] Architecture diagrams for parallel processing flow
- [ ] API documentation for new configuration options
- [ ] Code comments explaining coordination logic

---

**Last Updated**: 2024-01-XX  
**Status**: Planning Phase  
**Next Review**: After Phase 1 completion