package com.xai.sudokupro.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.util.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only endpoint for inspecting runtime constants.
 * Secured by {@code /admin/**} → ADMIN role in SecurityConfig.
 *
 * Previously embedded inside Constants.java — extracted to follow the
 * single-responsibility principle and keep controllers in the controller package.
 */
@RestController
@RequestMapping("/admin/constants")
public class ConstantsAdminController {

    private final Constants constants;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    public ConstantsAdminController(Constants constants) {
        this.constants = constants;
    }

    @GetMapping
    public Map<String, Object> getConstants() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("XP Config", Map.of(
            "XP Per Level",        constants.getXpPerLevel(),
            "XP Per Solve Easy",   constants.getXpPerSolveEasy()
        ));
        return config;
    }

    @GetMapping("/hash")
    public String getHash() {
        return constants.getIntegrityHash();
    }

    @PostMapping("/reload")
    public String reload() {
        return "Reload triggered";
    }

    @GetMapping("/export")
    public ResponseEntity<String> exportJson() throws Exception {
        String json = MAPPER.writeValueAsString(constants);
        return ResponseEntity.ok().body(json);
    }
}
