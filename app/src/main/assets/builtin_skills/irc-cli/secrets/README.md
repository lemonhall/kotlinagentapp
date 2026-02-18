# IRC secrets（本机私密配置）

这里的文件会被安装到真机的：

- `.agents/skills/irc-cli/secrets/`

请只在**真机本地**修改 `.env`，不要把任何真实密码/Token 提交到 git。

## 你需要改什么

编辑：

- `.agents/skills/irc-cli/secrets/.env`

最小必填（不能为空）：

- `IRC_SERVER`：例如 `irc.lemonhall.me`
- `IRC_PORT`：例如 `6697`
- `IRC_TLS`：`1`(TLS) / `0`(明文)
- `IRC_CHANNEL`：必须是合法频道名，通常要带 `#`，例如 `#lemon`
- `IRC_NICK`：**长度必须 <= 9**

可选：

- `IRC_SERVER_PASSWORD`：服务器 PASS（如不需要留空）
- `IRC_CHANNEL_KEY`：频道 key（有密码的频道才填）
- `IRC_NICKSERV_PASSWORD`：NickServ 密码（如不需要留空）
- `IRC_AUTO_FORWARD_TO_AGENT`：`1` 开启自动递送给 agent；默认 `0`

## 常见坑

- `No such channel (403)`：通常是 `IRC_CHANNEL` 写错（最常见：忘了 `#`、有空格、全角字符）。
- `Nickname already in use (433)`：换一个 `IRC_NICK`（仍需 <= 9）。

