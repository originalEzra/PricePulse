package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertTriggerMode;
import com.fasterxml.jackson.annotation.JsonProperty;

record ModelAlertDraft(
        @JsonProperty(required = true) boolean complete,
        @JsonProperty(required = true) String clarificationQuestion,
        @JsonProperty(required = true) String symbol,
        @JsonProperty(required = true) String market,
        @JsonProperty(required = true) AlertDirection direction,
        @JsonProperty(required = true) String thresholdPrice,
        @JsonProperty(required = true) AlertTriggerMode triggerMode,
        @JsonProperty(required = true) String rearmPrice,
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) String note) {
}
