---
name: wapo-rss
description: 华盛顿邮报（The Washington Post）RSS 订阅指引（协议/字段/更新频率/Feed 地址清单）。
---

# wapo-rss（The Washington Post RSS 指引）

## Goal

提供一份“纯文本可复制”的华盛顿邮报 RSS 订阅指引，用于后续基于 `rss` CLI（计划于 v24/v25）接入新闻源。

## 协议信息（以 2026-02-18 抓取结果为准）

- 协议：RSS 2.0（`<rss version="2.0">`）
- Content-Type：`application/rss+xml`
- 常见 XML 命名空间：
  - `xmlns:atom="http://www.w3.org/2005/Atom"`（self link）
  - `xmlns:content="http://purl.org/rss/1.0/modules/content/"`（正文；但华邮通常不提供全文）
  - `xmlns:dc="http://purl.org/dc/elements/1.1/"`（作者：`dc:creator`）
  - `xmlns:sy="http://purl.org/rss/1.0/modules/syndication/"`（更新频率声明）

## 更新频率字段

- `<sy:updatePeriod>`: `hourly`
- `<sy:updateFrequency>`: `1`
- `<ttl>`: `1`（分钟）

建议：拉取间隔不低于 1 分钟。

## 单条 item 常见字段

- `title`：文章标题
- `link`：文章链接
- `guid`：通常与 link 相同（`isPermaLink="true"`）
- `dc:creator`：作者（多作者逗号分隔）
- `pubDate`：RFC 2822（例如 `Wed, 18 Feb 2026 01:30:32 +0000`）
- `description`：摘要/导语

注意：通常没有 `content:encoded` 全文，只有 `description` 摘要；也常见没有图片/缩略图字段。

## Feed 地址汇总

华邮存在两种 URL 前缀格式，都可用：

- 新格式：`https://www.washingtonpost.com/arcio/rss/...`
- 旧格式：`https://feeds.washingtonpost.com/rss/...` 或 `http://feeds.washingtonpost.com/rss/...`

### 主分类

- Politics（政治）：`https://www.washingtonpost.com/arcio/rss/category/politics/`
- Opinions（观点）：`https://www.washingtonpost.com/arcio/rss/category/opinions/`
- Local（本地/华盛顿特区）：`https://feeds.washingtonpost.com/rss/local`
- Sports（体育）：`https://www.washingtonpost.com/arcio/rss/category/sports/`
- Technology（科技）：`https://feeds.washingtonpost.com/rss/business/technology`
- National（美国国内）：`http://feeds.washingtonpost.com/rss/national`
- World（国际）：`https://feeds.washingtonpost.com/rss/world`
- Business（商业）：`http://feeds.washingtonpost.com/rss/business`
- Lifestyle（生活方式）：`https://feeds.washingtonpost.com/rss/lifestyle`
- Entertainment（娱乐）：`http://feeds.washingtonpost.com/rss/entertainment`

### 政治子分类

- The Fix（政治博客）：`http://feeds.washingtonpost.com/rss/rss_the-fix`

### 观点子分类（按作者）

- George F. Will：`https://www.washingtonpost.com/arcio/rss/author/George%20F%20-Will/`

### 本地子分类

- Capital Weather Gang（天气）：`http://feeds.washingtonpost.com/rss/rss_capital-weather-gang`
- The Optimist：`http://feeds.washingtonpost.com/rss/national/inspired-life`
- Retropolis（历史）：`https://www.washingtonpost.com/arcio/rss/category/history/`

### 体育子分类

- High School Sports：`http://feeds.washingtonpost.com/rss/rss_recruiting-insider`
- DC Sports Bog：`http://feeds.washingtonpost.com/rss/rss_dc-sports-bog`
- Maryland Terrapins：`http://feeds.washingtonpost.com/rss/rss_terrapins-insider`
- Soccer（足球）：`http://feeds.washingtonpost.com/rss/rss_soccer-insider`
- Washington Commanders（NFL）：`http://feeds.washingtonpost.com/rss/rss_football-insider`
- Washington Capitals（NHL）：`http://feeds.washingtonpost.com/rss/rss_capitals-insider`
- Washington Nationals（MLB）：`http://feeds.washingtonpost.com/rss/rss_nationals-journal`
- Washington Wizards（NBA）：`http://feeds.washingtonpost.com/rss/rss_wizards-insider`

### 娱乐子分类

- Going Out Guide：`http://feeds.washingtonpost.com/rss/rss_going-out-gurus`

## 使用须知

- RSS Terms of Service：`https://www.washingtonpost.com/discussions/2021/01/01/rss-terms-service/`
- 官方 RSS 列表页：`https://www.washingtonpost.com/discussions/2018/10/12/washington-post-rss-feeds/`
- Feed 通常只提供摘要，不提供全文；获取全文需访问原文链接（部分文章可能需要订阅/付费）

## 后续（与 rss CLI 的衔接）

当 `rss` CLI 实现后，可用类似命令订阅（示例）：

- `rss add --name wapo-politics --url https://www.washingtonpost.com/arcio/rss/category/politics/`
- `rss fetch --name wapo-politics --max-items 20 --out artifacts/rss/wapo-politics-items.json`

## Rules

- 本指引为静态文档；如 feed 结构变化，以站点官方列表页为准并更新本 skill。

