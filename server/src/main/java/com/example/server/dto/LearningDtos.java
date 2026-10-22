package com.example.server.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

/** Learning API never accepts correctness, ownership or a timestamp from the browser. */
public final class LearningDtos {
    private LearningDtos() { }

    public record GenerateRequest(@NotNull @Positive Long mediaId,
                                  @NotBlank @Size(max = 500) String goal) { }
    public record AnswerRequest(@NotBlank @Size(max = 128) String answer,
                                @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                                String requestId) { }

    public record SegmentRow(String id, Long mediaId, long startMs, long endMs,
                             String transcript, String ocrText) { }
    public record ExerciseRow(String id, Long userId, String scopeDigest, String segmentId,
                              long segmentStartMs, long segmentEndMs, long timestampMs,
                              String prompt, String expectedAnswer, String knowledgePoint, String evidenceText) { }
    public record ExerciseView(String id, String segmentId, long timestampMs, String prompt) {
        public static ExerciseView from(ExerciseRow row) {
            return new ExerciseView(row.id(), row.segmentId(), row.timestampMs(), row.prompt());
        }
    }
    public record AttemptRow(Long id, Long userId, String requestId, String exerciseId,
                             String submittedAnswer, boolean correct, LocalDateTime answeredAt) { }
    public record AttemptView(Long id, String exerciseId, String prompt, String submittedAnswer,
                              boolean correct, String expectedAnswer, String knowledgePoint, String evidenceText,
                              String segmentId, long timestampMs, long segmentStartMs, long segmentEndMs,
                              LocalDateTime answeredAt) { }
}
