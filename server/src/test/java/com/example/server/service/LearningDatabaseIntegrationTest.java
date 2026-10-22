package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.dto.LearningDtos.*;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.LearningMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.util.*;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL + Flyway + MyBatis + Spring transactional proxy; no H2 substitution. */
@EnabledIfEnvironmentVariable(named="LEARNING_TEST_DB_URL", matches="jdbc:mysql:.*")
class LearningDatabaseIntegrationTest {
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private LearningService service;
    private LearningMapper mapper;
    private AgentCheckpointService checkpoints;
    private Long userId,otherUserId,mediaId;

    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeAll static void migrateRealDatabase() {
        String url=System.getenv("LEARNING_TEST_DB_URL");
        // Never clean or modify an arbitrary application database. Require an explicit test database.
        if (!url.matches("jdbc:mysql://[^/]+/dovideo_learning_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Integration tests require database dovideo_learning_test");
        }
        dataSource=new DriverManagerDataSource(url,System.getenv("LEARNING_TEST_DB_USERNAME"),System.getenv("LEARNING_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
    }
    private void openContext() {
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean("dataSource",DriverManagerDataSource.class,()->dataSource);
        context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(dataSource));
        context.registerBean("sqlSessionFactory",SqlSessionFactory.class,()->{
            try {
                SqlSessionFactoryBean factory=new SqlSessionFactoryBean();
                factory.setDataSource(dataSource);
                SqlSessionFactory result=factory.getObject();
                result.getConfiguration().addMapper(LearningMapper.class);
                return result;
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        context.registerBean("sqlSessionTemplate",SqlSessionTemplate.class,
                ()->new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)));
        context.registerBean("learningMapper",LearningMapper.class,
                ()->spy(context.getBean(SqlSessionTemplate.class).getMapper(LearningMapper.class)));
        context.registerBean("checkpoints",AgentCheckpointService.class,()->mock(AgentCheckpointService.class));
        context.registerBean("learningService",LearningService.class,()->new LearningService(context.getBean(LearningMapper.class),
                context.getBean(AgentCheckpointService.class),new EvidenceVerificationService(),new ObjectMapper()));
        context.refresh();
        mapper=context.getBean(LearningMapper.class);
        checkpoints=context.getBean(AgentCheckpointService.class);
        service=context.getBean(LearningService.class);
    }
    @BeforeEach void prepare() {
        userId=insertUser();
        otherUserId=insertUser();
        jdbc.update("INSERT INTO media_files(user_id,filename,status,file_path) VALUES(?,?,?,?)",userId,"learning.mp4","COMPLETED","video.mp4");
        mediaId=jdbc.queryForObject("SELECT id FROM media_files WHERE user_id=?",Long.class,userId);
        openContext();
        var segments=List.of(new VideoContext.VideoSegment(120000,180000,"Binary trees have roots",List.of(),List.of()),
                new VideoContext.VideoSegment(180000,240000,"Traversal visits every node",List.of(),List.of()));
        when(checkpoints.loadContext(mediaId)).thenReturn(new VideoContext("video.mp4","",segments));
        var evidence=List.of(new AnalysisResult.Evidence(125000,"ASR","Binary trees have roots","树的结构"),
                new AnalysisResult.Evidence(185000,"ASR","Traversal visits every node","遍历"));
        when(checkpoints.loadResult(mediaId,"goal",AnalysisMode.LEARNING)).thenReturn(new AgentState("goal",null,
                new AnalysisResult("title",List.of("树的结构","遍历"),evidence,List.of(),List.of()),
                new AgentState.CriticResult(true,List.of(),List.of(),List.of(),List.of()),1));
    }
    private Long insertUser() {
        String name="learn_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        jdbc.update("INSERT INTO users(username,password,nickname) VALUES(?,?,?)",name,"test-only",name);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
    }
    @AfterEach void cleanupOnlyOwnFixtures() {
        if (context!=null) context.close();
        if (userId!=null) {
            jdbc.update("DELETE FROM media_files WHERE user_id=?",userId);
            jdbc.update("DELETE FROM users WHERE id=?",userId);
        }
        if (otherUserId!=null) jdbc.update("DELETE FROM users WHERE id=?",otherUserId);
    }
    private int count(String table) {
        if (table.equals("learning_video_segments")) return jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_video_segments WHERE media_id=?",Integer.class,mediaId);
        if (!Set.of("learning_exercises","learning_attempts").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,userId);
    }
    @Test void persistsWrongAnswerAndRestoresFromNewApplicationContextWithoutRedisOrCheckpoint() {
        var questions=service.generate(mediaId,"goal",userId);
        assertEquals(2,questions.size());
        var result=service.answer(questions.getFirst().id(),"wrong",UUID.randomUUID().toString(),userId);
        assertFalse(result.correct());
        assertNotNull(result.answeredAt());
        context.close();
        openContext();
        assertEquals(2,service.exercises(mediaId,"goal",userId).size());
        var restored=service.history(mediaId,"goal",userId,true,null);
        assertEquals(result,restored.getFirst());
        assertTrue(restored.getFirst().timestampMs()>=restored.getFirst().segmentStartMs());
        assertTrue(restored.getFirst().timestampMs()<restored.getFirst().segmentEndMs());
        assertEquals(2,count("learning_video_segments"));
        assertEquals(1,count("learning_attempts"));
    }
    @Test void deniesOtherUsersAcrossEveryLearningPathAndSqlJoin() {
        var question=service.generate(mediaId,"goal",userId).getFirst();
        service.answer(question.id(),"wrong",UUID.randomUUID().toString(),userId);
        assertThrows(BusinessException.class,()->service.generate(mediaId,"goal",otherUserId));
        assertThrows(BusinessException.class,()->service.exercises(mediaId,"goal",otherUserId));
        assertThrows(BusinessException.class,()->service.history(mediaId,"goal",otherUserId,false,null));
        assertThrows(BusinessException.class,()->service.answer(question.id(),"wrong",UUID.randomUUID().toString(),otherUserId));
        assertNull(mapper.exercise(question.id(),otherUserId));
        assertTrue(mapper.exercises(mediaId,otherUserId,"ignored").isEmpty());
        assertEquals(1,count("learning_attempts"));
    }
    @Test void realSqlFailureOnSecondQuestionRollsBackFirstQuestionAndAllSnapshots() {
        AtomicInteger writes=new AtomicInteger();
        doAnswer(call->{
            ExerciseRow row=call.getArgument(0);
            if (writes.incrementAndGet()==2) {
                // Deliberate FK violation through the real MyBatis mapper, after earlier real inserts.
                ExerciseRow invalid=new ExerciseRow(row.id(),row.userId(),row.scopeDigest(),"0".repeat(64),
                        row.segmentStartMs(),row.segmentEndMs(),row.timestampMs(),row.prompt(),row.expectedAnswer(),row.knowledgePoint(),row.evidenceText());
                return context.getBean(SqlSessionTemplate.class).getMapper(LearningMapper.class).insertExercise(invalid);
            }
            return call.callRealMethod();
        }).when(mapper).insertExercise(any());
        assertThrows(DataIntegrityViolationException.class,()->service.generate(mediaId,"goal",userId));
        assertEquals(0,count("learning_video_segments"));
        assertEquals(0,count("learning_exercises"));
    }
    @Test void failureReadingWrittenAnswerRollsBackAttempt() {
        var question=service.generate(mediaId,"goal",userId).getFirst();
        when(mapper.attemptByRequest(eq(userId),anyString())).thenReturn(null);
        assertThrows(BusinessException.class,()->service.answer(question.id(),"wrong",UUID.randomUUID().toString(),userId));
        assertEquals(0,count("learning_attempts"));
    }
    @Test void databaseRejectsInvalidTimestampAndMissingSegmentAndWrongAttemptOwner() {
        var question=service.generate(mediaId,"goal",userId).getFirst();
        var row=mapper.exercise(question.id(),userId);
        // MySQL reports CHECK failures as HY000/3819; Spring may leave them uncategorized.
        var rejectedTimestamp=assertThrows(DataAccessException.class,()->mapper.insertExercise(new ExerciseRow("f".repeat(64),userId,
                row.scopeDigest(),row.segmentId(),row.segmentStartMs(),row.segmentEndMs(),row.segmentEndMs(),
                row.prompt(),row.expectedAnswer(),row.knowledgePoint(),row.evidenceText())));
        var sqlFailure=assertInstanceOf(SQLException.class,rejectedTimestamp.getMostSpecificCause());
        assertEquals(3819,sqlFailure.getErrorCode());
        assertTrue(sqlFailure.getMessage().contains("ck_learning_exercise_timestamp"));
        assertThrows(DataIntegrityViolationException.class,()->mapper.insertExercise(new ExerciseRow("e".repeat(64),userId,
                row.scopeDigest(),"0".repeat(64),row.segmentStartMs(),row.segmentEndMs(),row.timestampMs(),
                row.prompt(),row.expectedAnswer(),row.knowledgePoint(),row.evidenceText())));
        assertThrows(DataIntegrityViolationException.class,()->mapper.insertAttempt(new AttemptRow(null,otherUserId,
                UUID.randomUUID().toString(),row.id(),"wrong",false,null)));
        assertEquals(0,count("learning_attempts"));
        assertEquals(2,count("learning_exercises"));
    }
    @Test void concurrentGenerationAndAnswerRetriesAreIdempotentAndHistoryFiltersCorrectness() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->service.generate(mediaId,"goal",userId));
            var second=pool.submit(()->service.generate(mediaId,"goal",userId));
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(2,count("learning_exercises"));
            var question=service.exercises(mediaId,"goal",userId).getFirst();
            String request=UUID.randomUUID().toString();
            var a=pool.submit(()->service.answer(question.id(),"wrong",request,userId));
            var b=pool.submit(()->service.answer(question.id(),"wrong",request,userId));
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(1,count("learning_attempts"));
            var row=mapper.exercise(question.id(),userId);
            service.answer(row.id(),row.expectedAnswer(),UUID.randomUUID().toString(),userId);
            assertEquals(1,service.history(mediaId,"goal",userId,true,null).size());
            var history=service.history(mediaId,"goal",userId,false,null);
            assertEquals(2,history.size());
            assertEquals(1,service.history(mediaId,"goal",userId,false,history.getFirst().id()).size());
            assertTrue(service.history(mediaId,"another goal",userId,false,null).isEmpty());
        } finally { pool.shutdownNow(); }
    }
}
