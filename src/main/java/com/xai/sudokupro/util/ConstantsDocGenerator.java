package com.xai.sudokupro.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Generates a comprehensive Markdown documentation file for Constants class,
 * including static fields, enums, and configurable properties.
 * Supports custom output paths and integrates with Spring for runtime values.
 */
public class ConstantsDocGenerator {
    private static final Logger log = LoggerFactory.getLogger(ConstantsDocGenerator.class);
    private static final int MODIFIER_MASK = Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL;

    public static void main(String[] args) {
        String outputPath = args.length > 0 ? args[0] : "constants.md";
        try (PrintWriter out = new PrintWriter(outputPath)) {
            log.info("Generating documentation at {}", outputPath);
            generateDocumentation(out);
            log.info("Successfully generated {}", outputPath);
        } catch (Exception e) {
            log.error("Failed to generate documentation at {}", outputPath, e);
            throw new RuntimeException("Constants documentation generation failed", e);
        }
    }

    private static void generateDocumentation(PrintWriter out) {
        out.println("# SudokuPro Constants Documentation");
        out.println("Generated on: " + java.time.LocalDateTime.now());
        out.println("This document details all constants, enums, and configurable properties used in SudokuPro.");

        // Static Constants
        out.println("## Static Constants");
        out.println("These are hardcoded game rules and settings.");
        for (Field field : Constants.class.getDeclaredFields()) {
            if ((field.getModifiers() & MODIFIER_MASK) == MODIFIER_MASK) {
                try {
                    out.printf("- **%s**: %s%n", field.getName(), field.get(null));
                } catch (IllegalAccessException e) {
                    log.warn("Could not access field: {}", field.getName(), e);
                    out.printf("- **%s**: [Inaccessible]%n", field.getName());
                }
            }
        }

        // Enums
        out.println("## Enumerations");
        out.println("Structured constants defining game mechanics.");

        out.println("### Difficulty Levels");
        out.println("| Name | Cells Removed | Description |");
        out.println("|------|---------------|-------------|");
        for (Constants.Difficulty diff : Constants.Difficulty.values()) {
            out.printf("| %s | %d | %s difficulty level |%n", diff.name(), diff.cellsRemoved, diff.name().toLowerCase());
        }

        out.println("### Streak Tiers");
        out.println("| Name | Solves Required | Title |");
        out.println("|------|-----------------|-------|");
        for (Constants.StreakTier tier : Constants.StreakTier.values()) {
            out.printf("| %s | %d | %s |%n", tier.name(), tier.getSolves(), tier.getTitle());
        }

        out.println("### Titles");
        out.println("| Name | Required Streak | Display Name |");
        out.println("|------|-----------------|--------------|");
        for (Constants.Title title : Constants.Title.values()) {
            out.printf("| %s | %d | %s |%n", title.name(), title.getRequiredStreak(), title.getName());
        }

        out.println("### Event Types");
        out.println("| Name | ID | Bonus XP |");
        out.println("|------|----|----------|");
        for (Constants.EventType event : Constants.EventType.values()) {
            out.printf("| %s | %d | %d |%n", event.name(), event.id, event.bonusXP);
        }

        // Configurable Properties (requires Spring context)
        out.println("## Configurable Properties");
        out.println("Loaded from `application.properties` with prefix `game.`. Values shown are runtime defaults.");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Constants.class)) {
            Constants constants = context.getBean(Constants.class);
            out.println("| Property | Value | Description |");
            out.println("|----------|-------|-------------|");
            for (Field field : Constants.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isEnum()) {
                    field.setAccessible(true);
                    try {
                        Object value = field.get(constants);
                        String desc = getFieldDescription(field.getName());
                        out.printf("| %s | %s | %s |%n", field.getName(), value, desc);
                    } catch (IllegalAccessException e) {
                        log.warn("Could not access configurable field: {}", field.getName(), e);
                        out.printf("| %s | [Inaccessible] | %s |%n", field.getName(), getFieldDescription(field.getName()));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load Spring context for configurable properties", e);
            out.println("> **Note**: Could not load runtime properties due to: " + e.getMessage());
        }
    }

    private static String getFieldDescription(String fieldName) {
        // Simple mapping for now—could be expanded with annotations or a resource file
        return switch (fieldName) {
            case "xpPerLevel" -> "XP required to level up";
            case "xpPerSolveEasy" -> "XP awarded for solving an easy puzzle";
            case "gemsPerDuelWin" -> "Gems earned per duel victory";
            case "timeAttackSeconds" -> "Seconds allotted for Time Attack mode";
            default -> "No description available";
        };
    }
}
