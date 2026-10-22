package com.example.server.mapper;

import com.example.server.dto.LearningDtos.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface LearningMapper {
    @Select("SELECT id FROM media_files WHERE id=#{mediaId} AND user_id=#{userId}")
    Long ownedMedia(@Param("mediaId") Long mediaId, @Param("userId") Long userId);
    // All feature writes serialize on the owned media row, including concurrent retries.
    @Select("SELECT id FROM media_files WHERE id=#{mediaId} AND user_id=#{userId} FOR UPDATE")
    Long lockOwnedMedia(@Param("mediaId") Long mediaId, @Param("userId") Long userId);

    @Select("""
        SELECT e.id, e.user_id AS userId, e.scope_digest AS scopeDigest, e.segment_id AS segmentId,
               e.segment_start_ms AS segmentStartMs, e.segment_end_ms AS segmentEndMs, e.timestamp_ms AS timestampMs,
               e.prompt, e.expected_answer AS expectedAnswer, e.knowledge_point AS knowledgePoint, e.evidence_text AS evidenceText
        FROM learning_exercises e JOIN learning_video_segments s ON s.id=e.segment_id
        JOIN media_files m ON m.id=s.media_id
        WHERE s.media_id=#{mediaId} AND m.user_id=#{userId} AND e.user_id=#{userId}
          AND e.scope_digest=#{scope} ORDER BY e.id
        """)
    List<ExerciseRow> exercises(@Param("mediaId") Long mediaId, @Param("userId") Long userId, @Param("scope") String scope);

    @Select("""
        SELECT e.id, e.user_id AS userId, e.scope_digest AS scopeDigest, e.segment_id AS segmentId,
               e.segment_start_ms AS segmentStartMs, e.segment_end_ms AS segmentEndMs, e.timestamp_ms AS timestampMs,
               e.prompt, e.expected_answer AS expectedAnswer, e.knowledge_point AS knowledgePoint, e.evidence_text AS evidenceText
        FROM learning_exercises e JOIN learning_video_segments s ON s.id=e.segment_id
        JOIN media_files m ON m.id=s.media_id
        WHERE e.id=#{id} AND e.user_id=#{userId} AND m.user_id=#{userId}
        """)
    ExerciseRow exercise(@Param("id") String id, @Param("userId") Long userId);

    @Select("""
        SELECT s.media_id FROM learning_exercises e JOIN learning_video_segments s ON s.id=e.segment_id
        JOIN media_files m ON m.id=s.media_id WHERE e.id=#{id} AND e.user_id=#{userId} AND m.user_id=#{userId}
        """)
    Long exerciseMedia(@Param("id") String id, @Param("userId") Long userId);

    @Select("SELECT id FROM learning_video_segments WHERE id=#{id}")
    String segmentId(String id);

    @Insert("""
        INSERT INTO learning_video_segments(id,media_id,start_ms,end_ms,transcript,ocr_text)
        VALUES(#{id},#{mediaId},#{startMs},#{endMs},#{transcript},#{ocrText})
        """)
    int insertSegment(SegmentRow row);

    @Insert("""
        INSERT INTO learning_exercises(id,user_id,scope_digest,segment_id,segment_start_ms,segment_end_ms,
          timestamp_ms,prompt,expected_answer,knowledge_point,evidence_text)
        VALUES(#{id},#{userId},#{scopeDigest},#{segmentId},#{segmentStartMs},#{segmentEndMs},#{timestampMs},
          #{prompt},#{expectedAnswer},#{knowledgePoint},#{evidenceText})
        """)
    int insertExercise(ExerciseRow row);

    @Select("""
        SELECT a.id,a.user_id AS userId,a.request_id AS requestId,a.exercise_id AS exerciseId,
          a.submitted_answer AS submittedAnswer,a.correct,a.answered_at AS answeredAt
        FROM learning_attempts a JOIN learning_exercises e ON e.id=a.exercise_id
        JOIN learning_video_segments s ON s.id=e.segment_id JOIN media_files m ON m.id=s.media_id
        WHERE a.user_id=#{userId} AND e.user_id=#{userId} AND m.user_id=#{userId} AND a.request_id=#{requestId}
        """)
    AttemptRow attemptByRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Insert("""
        INSERT INTO learning_attempts(user_id,request_id,exercise_id,submitted_answer,correct)
        VALUES(#{userId},#{requestId},#{exerciseId},#{submittedAnswer},#{correct})
        """)
    int insertAttempt(AttemptRow row);

    @Select("""
        <script>
        SELECT a.id,e.id AS exerciseId,e.prompt,a.submitted_answer AS submittedAnswer,a.correct,
          e.expected_answer AS expectedAnswer,e.knowledge_point AS knowledgePoint,e.evidence_text AS evidenceText,
          s.id AS segmentId,e.timestamp_ms AS timestampMs,s.start_ms AS segmentStartMs,s.end_ms AS segmentEndMs,
          a.answered_at AS answeredAt
        FROM learning_attempts a JOIN learning_exercises e ON e.id=a.exercise_id
        JOIN learning_video_segments s ON s.id=e.segment_id JOIN media_files m ON m.id=s.media_id
        WHERE a.user_id=#{userId} AND e.user_id=#{userId} AND m.user_id=#{userId}
          AND m.id=#{mediaId} AND e.scope_digest=#{scope}
          <if test='wrongOnly'>AND a.correct=FALSE</if>
          <if test='beforeId != null'>AND a.id &lt; #{beforeId}</if>
        ORDER BY a.id DESC LIMIT 50
        </script>
        """)
    List<AttemptView> history(@Param("mediaId") Long mediaId, @Param("userId") Long userId,
                              @Param("scope") String scope, @Param("wrongOnly") boolean wrongOnly,
                              @Param("beforeId") Long beforeId);
}
