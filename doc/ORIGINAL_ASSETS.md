# 原创示例素材说明（橘团）

## 素材信息

- **角色名称**：橘团（jutuan）— 圆滚滚的像素橘猫
- **加入日期**：2026-11-04
- **制作方式**：使用 AI 图像生成工具为本项目专门创作（1 张基础形象 + 3 张姿势变体，程序拼装为标准 8 列 × 9 行精灵表）
- **权利归属**：本项目原创素材，随源代码以 GNU GPL v3 分发，不存在第三方角色、商标或参考作品的权利问题

## 与社区示例素材的区别

`assets/pets/` 下的其他示例宠物（胡桃、芙宁娜等）来源于 Codex Pet 社区，仅供学习与功能演示，版权归原作者所有（见 README 免责声明第 4 条）。**橘团是唯一由本项目原创、无版权风险的示例宠物。**

若希望发布完全无风险的构建版本，可移除其他示例目录，仅保留 `pets/jutuan/`。

## 格式规格

- 精灵表：`spritesheet.webp`，1536×1872（8 列 × 9 行，格子 192×208），无损 WebP
- 动画：idle 6 帧 / running-right 8 帧 / running-left 8 帧 / waving 4 帧 / jumping 5 帧 / failed 8 帧 / waiting 6 帧 / running 6 帧 / review 6 帧
- `pet.json` 未定义 `states`，使用 `PetAnimationState.DEFAULT_STATE_MAP` 默认映射
