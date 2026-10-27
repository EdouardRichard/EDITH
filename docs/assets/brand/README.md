# EDITH App 图标母版

本目录是手机端（`phone-app`）与眼镜端（`glasses-app`）App 图标的唯一母版来源。构建只读取各端 `res/` 下的生成物，
不引用仓库外路径；两端的图标资源必须同时更新（每个 APK 各自打包自己的 launcher icon）。

## 文件

| 文件 | 说明 |
| --- | --- |
| `logo_edith.png` | 业主提供的原始图，原样字节复制，未修改。1060×1060，1,191,383 B，SHA-256 `FD6696627067D699965709AF46EF6AD14CF802DE7907C3524684DBA1EEDBA16D`。 |
| `logo_edith_icon.png` | 去背景母版：1060×1060 RGBA，棋盘格背景已剔除，人物以外为透明。SHA-256 `435FBAA1318D355159565D3F1BA12C6410AF43D5811362564282AAD7602D0943`。 |

## 背景处理（业主 2026-10-22 决定：**白底**）

原图是全不透明 PNG：可见的灰白棋盘格是**画进像素的**（两色约 `#D5D5D3` / `#FDFDFD`，32 px 方格），
不是 alpha 通道；直接把原图当图标会把棋盘格一起画进桌面图标。`logo_edith_icon.png` 是剔除棋盘格后的
**透明抠图母版**，生成方式：

1. 颜色掩码：像素与两种棋盘格颜色任一的最大通道差 ≤ 14。
2. 从上、左、右边界做 4-连通泛洪（底部最后 2 行不参与：原图底边有 JPEG 压缩条纹，直接参与会把白衬衫当成背景）。
3. 再从已填充区域向外扩张，容差放宽到 30，补掉方格交界处的压缩过渡像素；底部最后 2 行保持不透明。
4. 其余像素保持 alpha=255。人物内部的白（脸、衬衫、高光）不与边界连通，因此完整保留。

**图标底板是白色，不是透明。** 首版曾把自适应图标背景设为透明；在眼镜端的黑色 UI 里，透明区域直接
透出黑底，观感是“黑底头像”，业主于同日改为白底。因此：方形回退图在生成时把透明母版合成为 `#FFFFFF`
底；自适应图标的 `<background>` 用 `@android:color/white`，前景仍是透明抠图（画作四周由白色底图层填充）。

## 生成物（两端 `res/`）

| 资源 | 密度/尺寸 | 用途 |
| --- | --- | --- |
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 48 / 72 / 96 / 144 / 192 px | 方形回退图标：**白底**，整幅画作。 |
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png` | 108 / 162 / 216 / 324 / 432 px | 自适应图标前景：108 dp 画布，画作占中央 72/108（2/3）可视区并居中，其余透明（由白色背景层填充）。 |
| `mipmap-anydpi-v26/ic_launcher.xml` | — | 自适应图标；背景层 `@android:color/white`。 |

两端 `AndroidManifest.xml` 的 `<application>` 使用 `android:icon="@mipmap/ic_launcher"` 与
`android:roundIcon="@mipmap/ic_launcher"`；minSdk 均为 28（≥26），因此 API 26+ 一律走自适应图标，方形 PNG 只作回退。

## 替换方式

替换 `logo_edith.png` 后按上文重新生成 `logo_edith_icon.png`，再按表中尺寸重采样并覆盖两端同名生成物；
`ic_launcher.xml` 不变。重采样须保持“画作占前景 72/108”的构图，否则圆形/方圆遮罩会裁到内容。
