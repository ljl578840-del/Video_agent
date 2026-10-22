package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.*;
import com.example.server.dto.LearningDtos.*;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.LearningMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class LearningService {
    private static final Pattern BLANK = Pattern.compile("[\\p{IsHan}]{2,4}|[A-Za-z]{3,}");
    private final LearningMapper mapper;
    private final AgentCheckpointService checkpoints;
    private final EvidenceVerificationService verification;
    private final ObjectMapper json;

    public LearningService(LearningMapper mapper, AgentCheckpointService checkpoints,
                           EvidenceVerificationService verification, ObjectMapper json) {
        this.mapper=mapper;
        this.checkpoints=checkpoints;
        this.verification=verification;
        this.json=json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public List<ExerciseView> generate(Long mediaId, String goal, Long userId) {
        String scope=scope(goal);
        requireOwned(mediaId,userId);
        List<ExerciseRow> existing=mapper.exercises(mediaId,userId,scope);
        if (!existing.isEmpty()) return existing.stream().map(ExerciseView::from).toList();
        AgentState state=checkpoints.loadResult(mediaId,goal.trim(),AnalysisMode.LEARNING);
        VideoContext context=checkpoints.loadContext(mediaId);
        if (state==null || state.result()==null || state.critique()==null || !state.critique().passed()
                || context==null) throw unreliable();
        // Build and validate the entire batch before the first insert. No guessed positions or parsed Markdown.
        Map<String,ExerciseRow> exercises=new LinkedHashMap<>();
        Map<String,SegmentRow> segments=new LinkedHashMap<>();
        for (AnalysisResult.Evidence evidence:state.result().evidence()) {
            if (exercises.size()>=10) break;
            if (evidence.content().length()>2000 || evidence.claim().isBlank() || evidence.claim().length()>2000
                    || state.result().conclusions().stream().noneMatch(
                        claim -> verification.supportsClaim(context,claim,evidence))) continue;
            VideoContext.VideoSegment segment=context.segments().stream().filter(s ->
                    evidence.timestampMs()>=s.startMs() && evidence.timestampMs()<s.endMs()
                    && literalSource(s,evidence)).findFirst().orElse(null);
            if (segment==null) continue;
            var blank=BLANK.matcher(evidence.content());
            if (!blank.find()) continue;
            String answer=blank.group();
            if (answer.length()>128) continue;
            String prompt="根据视频原文填空（填写 ____ 处原词）：\n"+evidence.content().substring(0,blank.start())
                    +"____"+evidence.content().substring(blank.end());
            String segmentId=digest(List.of(mediaId,segment.startMs(),segment.endMs(),segment.transcript(),segment.ocrTexts()));
            // ASR/OCR is aligned to windows, not words. Use the observed window start, not LLM precision.
            long timestampMs=segment.startMs();
            String id=digest(List.of("cloze-v1",userId,scope,segmentId,timestampMs,evidence.content(),evidence.claim()));
            exercises.putIfAbsent(id,new ExerciseRow(id,userId,scope,segmentId,segment.startMs(),segment.endMs(),
                    timestampMs,prompt,answer,evidence.claim(),evidence.content()));
            segments.putIfAbsent(segmentId,new SegmentRow(segmentId,mediaId,segment.startMs(),segment.endMs(),
                    segment.transcript(),String.join("\n",segment.ocrTexts())));
        }
        if (exercises.isEmpty()) throw unreliable();
        for (SegmentRow segment:segments.values()) {
            if (mapper.segmentId(segment.id())==null) requireWrite(mapper.insertSegment(segment));
        }
        for (ExerciseRow exercise:exercises.values()) requireWrite(mapper.insertExercise(exercise));
        return exercises.values().stream().sorted(Comparator.comparing(ExerciseRow::id))
                .map(ExerciseView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ExerciseView> exercises(Long mediaId, String goal, Long userId) {
        requireReadable(mediaId,userId);
        return mapper.exercises(mediaId,userId,scope(goal)).stream().map(ExerciseView::from).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AttemptView answer(String exerciseId, String answer, String requestId, Long userId) {
        if (answer==null || answer.isBlank() || answer.length()>128) {
            throw new IllegalArgumentException("答案不能为空且不能超过 128 字");
        }
        String canonicalRequest;
        try {
            canonicalRequest=UUID.fromString(requestId).toString();
            if (!canonicalRequest.equalsIgnoreCase(requestId)) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IllegalArgumentException("requestId 必须是完整 UUID"); }
        Long mediaId=mapper.exerciseMedia(exerciseId,userId);
        if (mediaId==null) throw missing();
        requireOwned(mediaId,userId);
        ExerciseRow exercise=mapper.exercise(exerciseId,userId);
        if (exercise==null) throw missing();
        AttemptRow attempt=mapper.attemptByRequest(userId,canonicalRequest);
        if (attempt!=null) {
            if (!attempt.exerciseId().equals(exerciseId) || !attempt.submittedAnswer().equals(answer.trim())) {
                throw new BusinessException(ErrorCode.CONFLICT,"该答题请求编号已用于其他答案，请刷新后重试");
            }
        } else {
            boolean correct=normalize(answer).equals(normalize(exercise.expectedAnswer()));
            requireWrite(mapper.insertAttempt(new AttemptRow(null,userId,canonicalRequest,exerciseId,answer.trim(),correct,null)));
            attempt=mapper.attemptByRequest(userId,canonicalRequest);
            if (attempt==null) throw new BusinessException(ErrorCode.INTERNAL_ERROR,"答题记录保存未完成，事务已取消，请重试");
        }
        return new AttemptView(attempt.id(),exercise.id(),exercise.prompt(),attempt.submittedAnswer(),attempt.correct(),
                exercise.expectedAnswer(),exercise.knowledgePoint(),exercise.evidenceText(),exercise.segmentId(),
                exercise.timestampMs(),exercise.segmentStartMs(),exercise.segmentEndMs(),attempt.answeredAt());
    }

    @Transactional(readOnly = true)
    public List<AttemptView> history(Long mediaId, String goal, Long userId, boolean wrongOnly, Long beforeId) {
        if (beforeId!=null && beforeId<=0) throw new IllegalArgumentException("beforeId 必须为正数");
        requireReadable(mediaId,userId);
        return mapper.history(mediaId,userId,scope(goal),wrongOnly,beforeId);
    }

    private void requireOwned(Long mediaId,Long userId) {
        if (mediaId==null || mediaId<=0 || userId==null || userId<=0
                || !mediaId.equals(mapper.lockOwnedMedia(mediaId,userId))) throw missing();
    }
    private void requireReadable(Long mediaId,Long userId) {
        if (mediaId==null || mediaId<=0 || userId==null || userId<=0
                || !mediaId.equals(mapper.ownedMedia(mediaId,userId))) throw missing();
    }
    private BusinessException missing() {
        return new BusinessException(ErrorCode.NOT_FOUND,"学习资源不存在或无权访问");
    }
    private BusinessException unreliable() {
        return new BusinessException(ErrorCode.UNPROCESSABLE,"没有通过校验的学习证据，无法生成带可靠时间戳的练习；请先完成学习模式分析");
    }
    private void requireWrite(int rows) {
        if (rows!=1) throw new BusinessException(ErrorCode.INTERNAL_ERROR,"学习记录未完整写入，事务已取消，请重试");
    }
    private boolean literalSource(VideoContext.VideoSegment s, AnalysisResult.Evidence e) {
        String source=e.source().toUpperCase(Locale.ROOT);
        return (source.contains("ASR") && s.transcript().contains(e.content()))
                || (source.contains("OCR") && s.ocrTexts().stream().anyMatch(t -> t.contains(e.content())));
    }
    private String scope(String goal) {
        if (goal==null || goal.isBlank() || goal.length()>500) throw new IllegalArgumentException("学习目标不能为空且不能超过 500 字");
        return digest(List.of("learning-v1",goal.trim()));
    }
    private String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) { throw new IllegalStateException("无法计算学习资源标识",e); }
    }
    private String normalize(String value) {
        return Normalizer.normalize(value.trim(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
