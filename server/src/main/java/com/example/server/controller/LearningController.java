package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.LearningDtos.*;
import com.example.server.service.AuthService;
import com.example.server.service.LearningService;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import com.example.server.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/analysis/learning")
public class LearningController {
    private static final Logger log=LoggerFactory.getLogger(LearningController.class);
    private final LearningService service;
    public LearningController(LearningService service) { this.service=service; }

    @PostMapping("/exercises")
    public Result<List<ExerciseView>> generate(@Valid @RequestBody GenerateRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.generate(request.mediaId(),request.goal(),userId));
    }
    @GetMapping("/exercises")
    public Result<List<ExerciseView>> exercises(@RequestParam Long mediaId, @RequestParam String goal,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.exercises(mediaId,goal,userId));
    }
    @PostMapping("/exercises/{id}/answers")
    public Result<AttemptView> answer(@PathVariable String id, @Valid @RequestBody AnswerRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.answer(id,request.answer(),request.requestId(),userId));
    }
    @GetMapping("/attempts")
    public Result<List<AttemptView>> history(@RequestParam Long mediaId, @RequestParam String goal,
            @RequestParam(defaultValue="false") boolean wrongOnly, @RequestParam(required=false) Long beforeId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.history(mediaId,goal,userId,wrongOnly,beforeId));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> databaseFailure(DataAccessException error) {
        log.error("learning_database_operation_failed",error);
        return ResponseEntity.status(503).body(Result.error(ErrorCode.SERVICE_UNAVAILABLE,
                "学习记录保存或读取失败，请重试；同一答题请求重试不会重复记账"));
    }
}
