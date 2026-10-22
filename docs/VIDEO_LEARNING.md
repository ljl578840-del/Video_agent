# 视频练习、知识点与错题关联

## 使用方式

1. 正常启动项目，Flyway 自动执行 `server/src/main/resources/db/migration/V4__create_video_learning.sql`。
2. 登录并上传视频，选择**学习复习（LEARNING）**模式完成分析。
3. 在结果面板的“视频练习与错题”中点击“生成 / 载入练习”。
4. 填写原词并提交；记录显示服务端判定、参考答案、对应知识点和原文证据。
5. 答错后点击“回看原视频”，复用原播放器跳到证据片段起点；“只看错题”过滤答错的尝试。
6. 刷新页面或重启后端，重新打开相同视频和分析目标，读取已有练习及学习记录。

现有 Agent 的 `quiz` 段落是非结构化 Markdown，不能可靠提取标准答案和关联位置。因此本功能提供**从核验过的原文生成填空题**的可答题入口，不把旧 Markdown 题目偷偷转换为有时间戳的题。它不调用新模型，也不做开放题语义判分。

## 数据与安全契约

- 题目只能从当前用户视频的 LEARNING 结果生成：Critic 必须通过；结论必须与 evidence.claim 对应；证据必须能在指定时间窗的原始 ASR/OCR 中核验，并且逐字包含于该来源。无可信候选返回 HTTP 422，零写入。
- `learning_video_segments` 持久化来源片段的文本与起止时间快照。SHA-256 ID 包含媒体、时间窗和原始文本；不是客户端或模型提交的 ID。
- `learning_exercises` 持久化知识点、原文、参考答案和关联片段。外键保证片段存在；复合外键和 CHECK 保证时间戳落在该片段范围。
- **时间精度为原管线的一分钟窗口**：返回 `segment.startMs`，不把 LLM 声称的秒数当作精确字词定位，也不在证据不足时回退到 0 秒。
- `learning_attempts` 每次提交保存答案、服务端正确性及数据库时间；查询记录不依赖 Redis 或 Agent 检查点，重建应用上下文仍可读取。
- API 沿用 `/analysis/**` 的 AuthInterceptor。当前用户只来自请求属性，不接受 body/query 传入的 userId。所有学习查询再次关联媒体归属；单条题目及记录操作失败返回不区分“不存在/无权”的 404。
- 生成题目和提交答案在 Spring 事务内完成，写入串行锁定对应的自有媒体；使用 READ_COMMITTED，避免等待锁后的幂等读取被旧一致性快照遮蔽。任何异常回滚本次事务，数据库错误对外返回明确 503，不泄露 SQL。
- 答案只做 NFKC 全半角、首尾空白和大小写归一；不将同义词猜作正确答案。
- `(user_id, request_id)` 唯一约束用于同一提交重试。相同 requestId、题目、答案返回同一记录，变更内容返回 409。浏览器在响应丢失时保留编号，不伪造本地成功记录。刷新后重新提交新编号是新的尝试。
- 单个目标最多生成 10 题，重复生成复用已保存的一组题目；历史按 ID 游标分页，每页最多 50 条。“错题”指答错的尝试，后续答对不会抹去旧错误记录。
- 练习快照不随后续分析覆盖；重新分析同一目标仍载入原练习。如需新的练习集，使用新的分析目标。删除视频会通过外键级联删除该视频的练习与答题记录。

实现文件：

- `server/src/main/java/com/example/server/service/LearningService.java`
- `server/src/main/java/com/example/server/mapper/LearningMapper.java`
- `server/src/main/java/com/example/server/controller/LearningController.java`
- `server/src/main/java/com/example/server/dto/LearningDtos.java`
- `server/src/main/resources/db/migration/V4__create_video_learning.sql`
- `client/src/LearningExercises.vue`、`client/src/learningExercises.js`、`client/src/App.vue`

## API 与手工复现

所有接口需 `Authorization: Bearer <token>`，使用原有 `{code,message,data}` 响应。以下为 Bash 示例，替换真实的自有 mediaId、目标和令牌：

```bash
API=http://127.0.0.1:9090
TOKEN='<登录获得的令牌>'

# 创建/复用当前目标的一组练习；返回 id、segmentId、timestampMs、prompt，不提前返回参考答案
curl -X POST "$API/analysis/learning/exercises" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"mediaId":1,"goal":"梳理视频知识点"}'

# 将 EXERCISE_ID 替换为生成结果中的 id；填写错误答案
curl -X POST "$API/analysis/learning/exercises/EXERCISE_ID/answers" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"answer":"故意答错","requestId":"049830b6-5a40-47cc-b60b-07044675c2c4"}'

# 原样重试上述提交，id 应保持相同；同编号改答案应返回 409
curl -G "$API/analysis/learning/attempts" -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'mediaId=1' --data-urlencode 'goal=梳理视频知识点' --data-urlencode 'wrongOnly=true'
```

验证持久化：记录答案响应中的 id，重启后端，再查询相同 mediaId/goal，应得到同一记录、知识点及片段时间。换另一用户令牌读取/提交该题，预期 404；无令牌预期 401。对未分析、Critic 未通过或证据无法匹配的视频生成，预期 422 且新表无新增行。可在响应查看 segmentStartMs ≤ timestampMs < segmentEndMs，并在播放器人工核验原文。

## 单元测试

```bash
cd server
./mvnw -s .mvn/central-settings.xml test
# Windows: .\mvnw.cmd -s .mvn/central-settings.xml test

cd ../client
npm ci
npm test
npm run build
```

受限环境若 Node 无法派生测试子进程，可用 `node --test --test-isolation=none src/*.test.js`；这是本次实际采用的运行模式。该参数需支持它的 Node 版本（本次为 24.17.0）；仓库原有 CI 使用 Node 22 和默认 `npm test`。

新增 `LearningServiceTest` 覆盖证据拒绝、真实片段关联、幂等、判分、写入失败及用户隔离；`LearningControllerTest` 覆盖鉴权、DTO 校验、身份来源、答案不提前暴露和数据库错误响应；`client/src/learningExercises.test.js` 覆盖错题展示数据、丢响应重试、历史加载、会话变更及无证据错误。

## 真实 MySQL 集成测试

`server/src/test/java/com/example/server/service/LearningDatabaseIntegrationTest.java` 使用真正的 MySQL、全部 Flyway 迁移、MyBatis SQL、Spring 事务代理，无替代数据库。只有提供 `LEARNING_TEST_DB_URL` 时启用；数据库名必须为 **dovideo_learning_test**，测试从不 clean 数据库，只清理自己创建的用户/媒体夹具。

使用隔离实例，**不要提供生产数据库连接**。以下示例仅供本地测试，端口 33306 与项目开发库分离：

```bash
docker run --rm -d --name dovideo-learning-test \
  -p 127.0.0.1:33306:3306 \
  -e MYSQL_ROOT_PASSWORD=learning-test-only \
  -e MYSQL_DATABASE=dovideo_learning_test mysql:8.0
# 等待 MySQL 可接受连接后：
export LEARNING_TEST_DB_URL='jdbc:mysql://127.0.0.1:33306/dovideo_learning_test?useSSL=false&allowPublicKeyRetrieval=true'
export LEARNING_TEST_DB_USERNAME=root
export LEARNING_TEST_DB_PASSWORD=learning-test-only
cd server
./mvnw -s .mvn/central-settings.xml test
# 完成后可停止自己创建的隔离容器：docker stop dovideo-learning-test
```

PowerShell 中用 `$env:LEARNING_TEST_DB_URL='...'` 等方式设置同名变量，再运行 `mvnw.cmd`。

六个集成场景：

1. 保存错误答案，关闭并重新创建应用上下文，无 Redis/Checkpoint 也能恢复记录。
2. 全部学习入口及 Mapper SQL 的跨用户隔离。
3. 第二道题通过真实 SQL 触发外键错误，之前的题目与片段全部回滚。
4. 答案插入后读取异常，答题事务全部回滚。
5. 数据库拒绝不存在片段、越界时间戳和其他用户的答题写入。
6. 并发生成与相同请求重试幂等，错题过滤、目标隔离和游标查询。

`.github/workflows/ci.yml` 已为 backend job 配置隔离 MySQL 服务与三个变量，后续 CI 会启用这些测试；本次没有触发远端 CI，不能宣称它已通过。

## 本次验证边界与上线注意

- 后端：49 项中 43 项通过、6 项集成测试跳过；没有失败。6 项已编译，但未取得专用 MySQL 凭据，Docker 引擎命名管道也拒绝访问，故没有实际执行数据库验证。
- 前端：30 项通过；App.vue 和 LearningExercises.vue 的脚本及模板编译检查通过。
- 生产构建：`npm run build` 在 Vite 配置加载处报 `spawn EPERM`，未完成；没有修改依赖来掩盖该限制。
- 未运行：真实视频/模型、浏览器端到端、实际服务器重启、真实 MySQL 集成和远端 CI。应用上下文重启验证也属于本次跳过的集成测试，不能当成已实测。
- MySQL 必须支持并执行 CHECK（MySQL ≥8.0.16；项目 Compose 使用 8.0）。历史表必须是支持外键的 InnoDB；部署前在测试环境执行迁移验证。
- 首次生成在持有媒体行锁期间读取现有检查点，缓存/数据库变慢会延长等待；没有新增模型请求。批量题目限制为 10 个，读历史限制为 50 条。
- 固定文本填空有明确判分边界，不支持旧 Markdown 开放题自动关联、同义答案语义判分和逐字时间对齐。没有证据的内容不会生成可跳转题目。

本功能未改视频管线、AgentLoop、MQ、鉴权框架或原有业务的结构；新增三张现有 MySQL 的业务表，没有引入新的 MQ、数据库产品或前端框架。
