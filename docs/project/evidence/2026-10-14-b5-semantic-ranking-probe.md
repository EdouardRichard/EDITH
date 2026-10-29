# 2026-10-14 B5 的真机"语义排序"探针（只记分数区间）

- **checkpoint**：`edith-0.2-phase-b-exec-20261014-09`
- **设备**：K40 `2696813b`（Android 13 / API 33）；手机 debug 构建（含本轮探针），眼镜未参与
- **授权**：业主 2026-10-14 授权 ADB/无障碍代操作
- **要回答的问题**：B5 一直卡在"**真机上检索是否按语义**"= `NotMeasured`。原因很具体：`P3_MEMORY inject=` 行
  只记条数/字符数/id，**不带分数**，而注入给模型的那个 `ContextPart` 本身也没有分数 ⇒
  "按语义排的"与"按字面（CJK 二元组）排的"在设备日志里**完全一样**。JVM 侧早就用假 embedding 证明了排序
  （`MemoryRetrievalWiringTest`），但那不是设备读数。
- **业主规则**：这类探针**只记分数区间**。

## 1. 交付的探针（TDD 先红后绿）

`P3_MEMORY inject=` 行现在多两个（外加一个诊断）字段：

```
P3_MEMORY inject=n=5 chars=144 via=semantic bucket=0.5-0.6 indexed=1 ids=memory:cad78234-…
```

| 字段 | 含义 |
|---|---|
| `via=semantic\|lexical` | **实际决定顺序的是哪条路**：至少有一条记忆是被**相似度**放进来的才算 `semantic` |
| `bucket=<区间>` | 最好的一条语义命中的**余弦相似度区间**（`none` = 没有语义命中） |
| `indexed=<n>` | 当前进程里向量索引有多少条（区分"没有服务/嵌不出来"与"索引是冷的"） |

实现：`VectorIndex.searchScored` → `SemanticRetriever.rankScored` → `MemoryModule.injectionProbe`
（`retrieve`/`injectionFor` 都是它的薄封装）→ `MemoryModuleRetrieval` 打行；区间由纯函数
`similarityBucket` 决定（半开区间，0.1 一档）。**没有正文、没有原始分数。**

## 2. 真机读数（同一次会话，连续回合，注入合成语音）

| # | 注入件 | `P3_MEMORY inject=` 读数（原文照抄，只删了 id 中段） |
|---|---|---|
| M1 | `E01-keep-tea.wav`（偏好句） | `n=5 chars=144 via=lexical bucket=none indexed=0` |
| M2 | `E06-keep-jazz.wav` | `n=5 chars=144 **via=semantic bucket=0.6-0.7 indexed=1**` |
| M3 | `R1b-query-tea.wav`（**相关**查询） | `n=5 chars=144 via=semantic **bucket=0.5-0.6** indexed=1` |
| M4 | `R3b-query-settings.wav`（**无关**查询） | `n=5 chars=144 via=semantic **bucket=0.4-0.5** indexed=1` |

M1 那一轮同时留下 `P3_MEMORY write=n=1 kind=PREFERENCE source=AUTO`（这一条就是 M2 起被索引的那条）。

**读出来的三件事**：

1. **冷索引不是"语义排序"，而且探针现在如实这么说。** M1 是重启后的第一个回合：库里有 5 条（跨会话持久化），
   但向量索引**是空的**——`SemanticRetriever` 只索引**本进程写过的**条目（它有意的设计，见其 KDoc）。
   探针的第一版在这里打的是 `via=semantic bucket=none`，那是在替一条**纯字面**顺序宣称"按语义"；
   真机读数直接暴露了它，已修（见 §3）。
2. **本进程写过的那条真的按语义参与排序**：M2 起 `indexed=1`、`via=semantic`，并且给出了**相似度区间**。
3. **区间跟着"意思有多近"走**：同一条被索引的记忆，**相关查询**落在 `0.5-0.6`，**无关查询**落在 `0.4-0.5`
   —— 这在设备上就是"排序用的是语义相似度、而不是字面重叠"的直接读数（K40 上 embedding 冒烟的参照量级：
   改写句 0.8586 / 无关句 0.3701）。

## 3. 真机暴露的探针缺陷（已修，TDD）

第一版把 `scored == 空表`（索引冷、语义排序"可用但一条都没放进来"）也算成 `semantic`。
真机一次读数（M1 的 `via=semantic bucket=none`）就把它照出来了。
修复：**只有真的被相似度放进来的条目才算 `semantic`**，并新增 `indexed=` 把"冷索引"与"服务不可用/嵌不出来"分开。
测试 `MemoryRetrievalWiringTest` 新增"从存储恢复的库必须报 lexical"（先红后绿）。
这与本项目反复出现的模式一致：**出问题的往往是判据/仪器本身，而不是被测的生产代码**。

## 4. 边界（不得读成已完成）

- **本轮没有在设备上造出"语义与字面排序相反"的排序对照**：一次只有一个条目在索引里（`indexed=1`），
  所以**头名不可能变** —— 变化的只有相似度区间。**排序层面的对照仍然只有 JVM 证据**
  （`MemoryRetrievalWiringTest`：字面最强的是"我不吃香菜"、语义最近的是另一条，生产路径注入的是后者）。
  要在设备上做出来，需要一个能在同一进程里写入 ≥2 条并给出"各自相关查询"的受控语料，本轮没有做。
- 注入语音是 Windows SAPI 合成 WAV 经 `edith.DEBUG_INJECT`（**非佩戴者发声**）；**正文与该条记忆的文本都不入本文件**
  （只记 id/计数/区间），符合 §5.3 与业主对探针的规则。
- 区间是"最好那条语义命中"的相似度；`n=5` 时其余条目仍按字面排在语义头之后（其顺序随查询变化，可从 id 顺序看出，
  但**本轮未把这一点作为判据**）。
- 未测：真实佩戴者语料下的排序质量；embedding 前向 ~584 ms 仍是既有未达标项；大于 1 条的索引排序。
