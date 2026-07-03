package com.xai.sudokupro.model.api;

import java.time.LocalDateTime;

/** Wire representation of an active live event (shared server/client). */
public record EventInfo(String eventId, LocalDateTime endTime) {}
