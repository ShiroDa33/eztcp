# eztcp

简易 TCP 连接探测服务，使用 Java 17 实现。通过 HTTP 提交一个或多个目标，后台建立 TCP 连接，随后通过任务 ID 查询结果。

HTTP 使用 JDK `HttpServer`，TCP 使用 JDK `Socket`

## 启动

需要 **JDK 17 或更高版本**、**Maven 3.9 或更高版本**。首次构建需要联网下载 Maven 依赖。

```sh
git clone https://github.com/ShiroDa33/eztcp.git
cd eztcp
mvn clean verify
java -jar target/eztcp.jar
```

默认监听 `8080` 端口。如需改为 `9090`：

```sh
java -Dport=9090 -jar target/eztcp.jar
```

按 `Ctrl+C` 停止服务。

## 快速验证

服务启动后，它自身的 `8080` 端口就是一个可连通的探测目标，无需访问外部网站。

以下命令用于 Bash 等 shell；PowerShell 示例见下方。

```sh
curl -i -X POST http://localhost:8080/tasks \
  -H 'Content-Type: application/json' \
  -d '{"targets":[{"host":"127.0.0.1","port":8080},{"host":"127.0.0.1","port":1}]}'
```

返回 `202 Accepted`，响应头 `Location: /tasks/<id>`，响应体：

```json
{"id":"返回的任务ID"}
```

将响应中的 ID 代入查询地址：

```sh
curl http://localhost:8080/tasks/返回的任务ID
```

如任务仍是 `RUNNING`，稍后再次查询。假设本机 `1` 端口没有监听，完成后的响应类似：

```json
{
  "id": "返回的任务ID",
  "status": "COMPLETED",
  "total": 2,
  "completed": 2,
  "results": [
    {"host":"127.0.0.1","port":8080,"reachable":true,"durationMs":1,"error":null},
    {"host":"127.0.0.1","port":1,"reachable":false,"durationMs":2,"error":"Connection refused"}
  ]
}
```

耗时和错误文字随网络环境、操作系统而变化。如果本机 `1` 端口正在监听，第二项也会成功。

PowerShell 可直接执行：

```powershell
$body = @{ targets = @(
    @{ host = '127.0.0.1'; port = 8080 },
    @{ host = '127.0.0.1'; port = 1 }
) } | ConvertTo-Json -Depth 3

$task = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/tasks' `
    -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "http://localhost:8080/tasks/$($task.id)" | ConvertTo-Json -Depth 4
```

## 接口约定

| 接口 | 用途 | 成功响应 |
| --- | --- | --- |
| `POST /tasks` | 提交 `{"targets":[{"host":"主机名或IP","port":端口}]}` | `202`，返回任务 `id` |
| `GET /tasks/{id}` | 查询任务进度及已完成结果 | `200`，返回任务详情 |

- `targets` 至少包含一个目标；`host` 不能为空，`port` 范围为 `1–65535`。单个目标和批量目标使用同一接口。
- 任务状态为 `RUNNING` 或 `COMPLETED`。`completed` 表示已经完成的目标数；`results` 只包含已完成项，并保留它们在输入中的相对顺序。
- `COMPLETED` 表示所有目标均已尝试探测；是否连通由各项 `reachable` 表示。TCP 连接成功即视为可达，不发送应用层数据。
- `durationMs` 是该目标从开始探测到得到结果的耗时，不含排队时间；失败时 `error` 给出连接错误。
- 输入错误返回 `400`；任务或路径不存在返回 `404`；请求方法不支持返回 `405`，并通过 `Allow` 响应头给出允许的方法。
- 错误响应格式为 `{"error":"错误说明"}`。

## 执行与限流

- **后台执行**：提交接口安排任务后立即返回，不等待 TCP 连接完成。固定线程池最多同时探测 **8** 个目标，其余目标排队。
- **连接超时**：每次 `Socket.connect` 超时为 **2 秒**。主机名的 DNS 解析在连接前执行，不受这个连接超时约束。
- **简单限流**：按服务器时间的固定一秒窗口，全局最多接受 **5 次 `POST /tasks` 请求**。批量请求计一次，格式错误的提交也占一次配额；查询接口不限流。
- 超出限额返回 `429`，带 `Retry-After: 1`，且不会创建任务。固定窗口在秒边界重新计数，因此边界两侧可能短时出现多于 5 次请求。
- 任务及结果仅保存在当前进程的内存中，服务重启后丢失。

可在 PowerShell 中并发提交，观察 `202` 和 `429`（应在快速验证完成至少一秒后执行）：

```powershell
$client = [System.Net.Http.HttpClient]::new()
$requests = 1..20 | ForEach-Object {
    $content = [System.Net.Http.StringContent]::new(
        '{"targets":[{"host":"127.0.0.1","port":8080}]}',
        [System.Text.Encoding]::UTF8, 'application/json')
    $client.PostAsync('http://localhost:8080/tasks', $content)
}
$requests | ForEach-Object { [int]$_.GetAwaiter().GetResult().StatusCode }
$client.Dispose()
```

## 测试和代码

```sh
mvn test
```

测试通过临时本地监听端口和未监听端口，验证批量任务、结果顺序和成功/失败结果；同时验证 HTTP 错误响应、提交限流、查询不受限流影响、窗口重置及并发计数。限流测试使用可控时钟，不依赖实际秒边界，也不访问外部探测目标。

```text
src/main/java/io/github/shiroda33/eztcp/
  TaskServer.java              HTTP 接口、JSON 和程序入口
  ProbeService.java            后台任务与 TCP 连接探测
  FixedWindowRateLimiter.java   固定窗口计数
src/test/java/io/github/shiroda33/eztcp/
  TaskServerTest.java
  FixedWindowRateLimiterTest.java
```
