package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertTriggerMode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

record ModelAlertDraft(
        @JsonProperty(required = true) boolean complete,
        @JsonProperty(required = true) String clarificationQuestion,
        @JsonProperty(required = true)
        @JsonPropertyDescription("The stock name, code, or fragment exactly as supplied by the user. Do not expand it into a guessed stock symbol.")
        String symbol,
        @JsonProperty(required = true) String market,
        @JsonProperty(required = true) AlertDirection direction,
        @JsonProperty(required = true) String thresholdPrice,
        @JsonProperty(required = true) AlertTriggerMode triggerMode,
        @JsonProperty(required = true) String rearmPrice,
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) String note) {
}
