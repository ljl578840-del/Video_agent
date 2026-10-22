package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.*;
import com.example.server.dto.LearningDtos.*;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.LearningMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningServiceTest {
    private final LearningMapper mapper=mock(LearningMapper.class);
    private final AgentCheckpointService checkpoints=mock(AgentCheckpointService.class);
    private final LearningService service=new LearningService(mapper,checkpoints,new EvidenceVerificationService(),new ObjectMapper());
    private final VideoContext context=new VideoContext("video.mp4","",List.of(
            new VideoContext.VideoSegment(120000,180000,"Binary trees have roots",List.of(),List.of())));
    private final String requestId=UUID.randomUUID().toString();

    @BeforeEach
    void setup() {
        when(mapper.lockOwnedMedia(1L,7L)).thenReturn(1L);
        when(mapper.ownedMedia(1L,7L)).thenReturn(1L);
        when(mapper.exercises(anyLong(),anyLong(),anyString())).thenReturn(List.of());
        when(mapper.insertSegment(any())).thenReturn(1);
        when(mapper.insertExercise(any())).thenReturn(1);
        when(checkpoints.loadContext(1L)).thenReturn(context);
        reliable(new AnalysisResult.Evidence(125000,"ASR","Binary trees have roots","树的结构"),true);
    }
    private void reliable(AnalysisResult.Evidence evidence,boolean passed) {
        when(checkpoints.loadResult(1L,"goal",AnalysisMode.LEARNING)).thenReturn(new AgentState("goal",null,
                new AnalysisResult("title",List.of("树的结构"),List.of(evidence),List.of(),List.of()),
                new AgentState.CriticResult(passed,List.of(),List.of(),List.of(),List.of()),1));
    }
    private ExerciseRow exercise() {
        return new ExerciseRow("exercise",7L,"scope","segment",120000,180000,125000,
                "____ trees have roots","Binary","树的结构","Binary trees have roots");
    }
    private void answerable() {
        when(mapper.exerciseMedia("exercise",7L)).thenReturn(1L);
        when(mapper.exercise("exercise",7L)).thenReturn(exercise());
        when(mapper.insertAttempt(any())).thenReturn(1);
    }

    @Test void generatesOnlyFromVerifiedSourceWithRealSegmentAndTimestamp() {
        List<ExerciseView> result=service.generate(1L," goal ",7L);
        assertEquals(1,result.size());
        assertEquals(120000,result.getFirst().timestampMs());
        assertTrue(result.getFirst().prompt().contains("____ trees"));
        ArgumentCaptor<SegmentRow> segment=ArgumentCaptor.forClass(SegmentRow.class);
        verify(mapper).insertSegment(segment.capture());
        assertEquals(segment.getValue().id(),result.getFirst().segmentId());
        assertEquals(120000,segment.getValue().startMs());
        assertEquals(context.segments().getFirst().transcript(),segment.getValue().transcript());
    }
    @Test void untrustedCriticCannotCreateRows() {
        reliable(new AnalysisResult.Evidence(125000,"ASR","Binary trees have roots","树的结构"),false);
        assertEquals(ErrorCode.UNPROCESSABLE,assertThrows(BusinessException.class,()->service.generate(1L,"goal",7L)).errorCode());
        verify(mapper,never()).insertSegment(any());
        verify(mapper,never()).insertExercise(any());
    }
    @Test void inventedTimeAndInventedTextCannotCreateRows() {
        for (AnalysisResult.Evidence evidence:List.of(
                new AnalysisResult.Evidence(999999,"ASR","Binary trees have roots","树的结构"),
                new AnalysisResult.Evidence(125000,"ASR","Invented knowledge","树的结构"))) {
            reliable(evidence,true);
            assertThrows(BusinessException.class,()->service.generate(1L,"goal",7L));
        }
        verify(mapper,never()).insertSegment(any());
    }
    @Test void absentContextAndEvidenceWithoutClaimAreRejected() {
        when(checkpoints.loadContext(1L)).thenReturn(null);
        assertThrows(BusinessException.class,()->service.generate(1L,"goal",7L));
        when(checkpoints.loadContext(1L)).thenReturn(context);
        reliable(new AnalysisResult.Evidence(125000,"ASR","Binary trees have roots",""),true);
        assertThrows(BusinessException.class,()->service.generate(1L,"goal",7L));
        verify(mapper,never()).insertExercise(any());
    }
    @Test void generationIsIdempotentAndDoesNotNeedCheckpointAgain() {
        when(mapper.exercises(eq(1L),eq(7L),anyString())).thenReturn(List.of(exercise()));
        assertEquals("exercise",service.generate(1L,"goal",7L).getFirst().id());
        verifyNoInteractions(checkpoints);
        verify(mapper,never()).insertExercise(any());
    }
    @Test void unauthorizedGenerationAndAnswerHaveNoWrites() {
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BusinessException.class,()->service.generate(1L,"goal",8L)).errorCode());
        assertThrows(BusinessException.class,()->service.answer("exercise","wrong",requestId,8L));
        verify(mapper,never()).insertAttempt(any());
        verifyNoInteractions(checkpoints);
    }
    @Test void gradesOnServerPersistsWrongAnswerAndReturnsKnowledge() {
        answerable();
        when(mapper.attemptByRequest(7L,requestId)).thenReturn(null,
                new AttemptRow(5L,7L,requestId,"exercise","wrong",false,LocalDateTime.now()));
        AttemptView result=service.answer("exercise","wrong",requestId,7L);
        assertFalse(result.correct());
        assertEquals("树的结构",result.knowledgePoint());
        assertEquals("segment",result.segmentId());
        assertEquals(125000,result.timestampMs());
        ArgumentCaptor<AttemptRow> row=ArgumentCaptor.forClass(AttemptRow.class);
        verify(mapper).insertAttempt(row.capture());
        assertFalse(row.getValue().correct());
        assertEquals(7L,row.getValue().userId());
    }
    @Test void normalizesWidthWhitespaceAndCaseButDoesNotGuessSynonyms() {
        answerable();
        when(mapper.attemptByRequest(7L,requestId)).thenReturn(null,
                new AttemptRow(5L,7L,requestId,"exercise","ＢＩＮＡＲＹ",true,LocalDateTime.now()));
        assertTrue(service.answer("exercise"," ＢＩＮＡＲＹ ",requestId,7L).correct());
        verify(mapper).insertAttempt(argThat(AttemptRow::correct));
    }
    @Test void retryReturnsSameAttemptAndChangedPayloadConflicts() {
        answerable();
        when(mapper.attemptByRequest(7L,requestId)).thenReturn(
                new AttemptRow(5L,7L,requestId,"exercise","wrong",false,LocalDateTime.now()));
        assertEquals(5L,service.answer("exercise","wrong",requestId,7L).id());
        assertEquals(ErrorCode.CONFLICT,assertThrows(BusinessException.class,
                ()->service.answer("exercise","changed",requestId,7L)).errorCode());
        verify(mapper,never()).insertAttempt(any());
    }
    @Test void malformedAnswerAndRequestAreRejectedBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class,()->service.answer("exercise"," ",requestId,7L));
        assertThrows(IllegalArgumentException.class,()->service.answer("exercise","x","bad",7L));
        assertThrows(IllegalArgumentException.class,()->service.answer("exercise","x","1-1-1-1-1",7L));
        verify(mapper,never()).exerciseMedia(anyString(),anyLong());
    }
    @Test void readFailureAfterInsertAndIncompleteWriteAreNotReportedAsSuccess() {
        answerable();
        when(mapper.attemptByRequest(7L,requestId)).thenReturn(null);
        assertThrows(BusinessException.class,()->service.answer("exercise","wrong",requestId,7L));
        when(mapper.insertExercise(any())).thenReturn(0);
        assertThrows(BusinessException.class,()->service.generate(1L,"goal",7L));
    }
    @Test void historyAndQuestionsRequireOwnershipAndHistoryUsesServerUser() {
        assertThrows(BusinessException.class,()->service.history(1L,"goal",8L,true,null));
        assertThrows(BusinessException.class,()->service.exercises(1L,"goal",8L));
        service.history(1L,"goal",7L,true,99L);
        verify(mapper).history(eq(1L),eq(7L),anyString(),eq(true),eq(99L));
        assertThrows(IllegalArgumentException.class,()->service.history(1L,"goal",7L,false,-1L));
    }
}
