# 第三方代码声明

## Pi Coding Agent

- 项目：[@earendil-works/pi-coding-agent](https://github.com/earendil-works/pi)
- 作者：Mario Zechner
- 许可证：MIT

本项目的 `app/src/main/java/com/codexrefresh/app/CodexClient.kt` 中，OpenAI Codex 设备码 OAuth 流程（接口端点、轮询与错误码处理、JWT claim 解析）以及 Codex 用量 / Responses 请求的请求头与 SSE 处理方式，参考并移植自 Pi Coding Agent 的实现。

以下为该项目的完整许可证文本：

```text
MIT License

Copyright (c) 2025 Mario Zechner

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
