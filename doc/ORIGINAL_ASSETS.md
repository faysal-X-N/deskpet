# 原创示例素材说明（橘团）

## 素材信息

- **角色名称**：橘团（jutuan）— 圆滚滚的像素橘猫
- **加入日期**：2026-11-04
- **制作方式**：使用 AI 图像生成工具为本项目专门创作（1 张基础形象 + 3 张姿势变体，程序拼装为标准 8 列 × 9 行精灵表）
- **权利归属**：本项目原创素材，随源代码以 GNU GPL v3 分发，不存在第三方角色、商标或参考作品的权利问题

## v1.2.0：内置素材只保留橘团

**决策**：从 `assets/pets/` 移除 feibi-2 / feixue / furina / hutao / ikun / jiyi / yi-bu 七个目录，
只保留 `pets/jutuan/`。

**原因**：那七只是 Codex Pet 社区素材（含《原神》等第三方角色形象）。把它们打进 APK 分发，
一是版权风险（GPL 只覆盖本项目自己的代码，不覆盖他人角色形象），二是开源商店审核的常见
拒收理由——IzzyOnDroid 会核对"无专有组件"，内置知名角色素材属于典型争议项。

**影响面**：只影响"开箱即得的示例数量"（8 只 → 1 只）。格式支持、导入、渲染、备份恢复
全部不变；用户导入这些素材包的效果与内置完全一致。

**想找回那七只**：它们仍在 git 历史里（`5abdcc4` 及之前），
`git show 5abdcc4:app/src/main/assets/pets/hutao/spritesheet.webp > hutao.webp` 即可取出，
打成 zip 后用「导入 Codex Pet 素材包」自行导入。仓库不保留副本，避免继续随包分发。

**已装旧版的用户**：`samples_loaded` 标记已落盘，升级后不会重复加载，也不会删除已导入的宠物。

## 格式规格

- 精灵表：`spritesheet.webp`，1536×1872（8 列 × 9 行，格子 192×208），无损 WebP
- 动画：idle 6 帧 / running-right 8 帧 / running-left 8 帧 / waving 4 帧 / jumping 5 帧 / failed 8 帧 / waiting 6 帧 / running 6 帧 / review 6 帧
- `pet.json` 未定义 `states`，使用 `PetAnimationState.DEFAULT_STATE_MAP` 默认映射
