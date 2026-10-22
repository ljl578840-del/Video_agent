package com.example.server.controller;

import com.example.server.config.AuthInterceptor;
import com.example.server.dto.LearningDtos.ExerciseView;
import com.example.server.service.AuthService;
import com.example.server.service.LearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LearningControllerTest {
    private final LearningService service=mock(LearningService.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        AuthService auth=mock(AuthService.class);
        when(auth.resolveUser(nullable(String.class))).thenThrow(new SecurityException("请先登录"));
        doReturn(7L).when(auth).resolveUser("Bearer owner");
        mvc=MockMvcBuilders.standaloneSetup(new LearningController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .addInterceptors(new AuthInterceptor(auth,new ObjectMapper())).build();
    }
    @Test void unauthenticatedLearningRequestsAreRejectedBeforeService() throws Exception {
        mvc.perform(get("/analysis/learning/attempts").param("mediaId","1").param("goal","goal"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        mvc.perform(post("/analysis/learning/exercises").contentType("application/json")
                .content("{\"mediaId\":1,\"goal\":\"goal\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void authenticatedIdentityIsUsedAndQuestionResponseDoesNotExposeAnswer() throws Exception {
        when(service.generate(1L,"goal",7L)).thenReturn(List.of(new ExerciseView("q1","s1",125000,"____ trees")));
        mvc.perform(post("/analysis/learning/exercises").header("Authorization","Bearer owner")
                .param("userId","99").contentType("application/json").content("{\"mediaId\":1,\"goal\":\"goal\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].segmentId").value("s1"))
                .andExpect(jsonPath("$.data[0].expectedAnswer").doesNotExist());
        verify(service).generate(1L,"goal",7L);
    }
    @Test void invalidAnswerDtoIsExplicit400AndDoesNotWrite() throws Exception {
        mvc.perform(post("/analysis/learning/exercises/q1/answers").header("Authorization","Bearer owner")
                .contentType("application/json").content("{\"answer\":\"\",\"requestId\":\"bad\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        verifyNoInteractions(service);
    }
    @Test void databaseFailureHasSafeAndActionableError() throws Exception {
        when(service.generate(1L,"goal",7L)).thenThrow(new DataIntegrityViolationException("private SQL details"));
        mvc.perform(post("/analysis/learning/exercises").header("Authorization","Bearer owner")
                .contentType("application/json").content("{\"mediaId\":1,\"goal\":\"goal\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300))
                .andExpect(jsonPath("$.message").value("学习记录保存或读取失败，请重试；同一答题请求重试不会重复记账"));
    }
}
