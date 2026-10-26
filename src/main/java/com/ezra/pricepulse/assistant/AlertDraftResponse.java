package com.ezra.pricepulse.assistant;

public record AlertDraftResponse(boolean ready, String clarificationQuestion, AlertRuleDraft draft) {

    static AlertDraftResponse ready(AlertRuleDraft draft) {
        return new AlertDraftResponse(true, null, draft);
    }

    static AlertDraftResponse clarification(String question) {
        return new AlertDraftResponse(false, question, null);
    }
}
