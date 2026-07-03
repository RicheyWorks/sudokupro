package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.api.EventInfo;
import com.xai.sudokupro.service.EventEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** Active live events, exposed for remote clients (AUDIT follow-up: client/server separation). */
@RestController
@Tag(name = "Events API")
public class EventsController {

    private final EventEngine eventEngine;

    public EventsController(EventEngine eventEngine) {
        this.eventEngine = eventEngine;
    }

    @Operation(summary = "Currently active events and their end times")
    @GetMapping("/api/events")
    public ResponseEntity<List<EventInfo>> activeEvents() {
        List<EventInfo> events = eventEngine.getActiveEvents().entrySet().stream()
            .map(e -> new EventInfo(e.getKey(), e.getValue().getEndTime()))
            .sorted(Comparator.comparing(EventInfo::endTime))
            .toList();
        return ResponseEntity.ok(events);
    }
}
