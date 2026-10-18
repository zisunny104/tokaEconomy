# tokaEconomy

多貨幣餘額、轉帳、兌換、排行榜與交易紀錄。輸入 `/balance` 開啟錢包選單；管理員可調整餘額及管理舊名稱帳戶。

需要 Java 21。tokaProfiles 的名稱對應尚未接入，目前使用線上玩家、已有帳戶及背景查詢的伺服器玩家檔。

- 帳戶以 UUID 為準，金額一律是整數 `long`，資料存在 `storage/sqlite`。
- 餘額先在記憶體更新，由單一背景執行緒存檔；關閉伺服器時最多等 10 秒寫完。
- 貨幣只認 `config.yml` 內的代碼；貨幣名稱寫在設定檔，不在語系檔。

## 玩家指令

| 指令 | 說明 | 權限與預設 |
| --- | --- | --- |
| `/balance`、`/bal`、`/money` | 開啟錢包視窗 | `tokaEconomy.balance`，所有人 |
| `/balance <玩家> [貨幣]` | 文字輸出；看別人需要 `tokaEconomy.see` | `tokaEconomy.balance`，所有人 |
| `/pay <玩家> <金額> [貨幣]` | 轉帳；不寫貨幣用主要貨幣；手續費由轉出方另付 | `tokaEconomy.pay`，所有人 |
| `/exchange <來源貨幣> <目標貨幣> <金額>` | 依匯率兌換，所得無條件捨去；不加參數列出匯率；未設定匯率時會說明 | `tokaEconomy.exchange`，所有人 |
| `/economy top [貨幣] [頁]`、`/topmoney` | 顯示餘額大於 0 的帳戶排行榜 | `tokaEconomy.top`，所有人 |

## 玩家視窗

輸入 `/balance` 開啟錢包選單，指定玩家時顯示文字餘額。選單依權限與匯率設定顯示可用功能。

```
錢包：各貨幣餘額
├─ 轉帳：選線上玩家 → 金額＋貨幣 → 確認 → 結果訊息
├─ 兌換：選貨幣組合 → 金額 → 確認 → 結果訊息
├─ 排行榜：選貨幣 → 分頁名次
└─ 交易紀錄：自己的紀錄，分頁
```

| 視窗 | 內容 |
| --- | --- |
| 選玩家 | 搜尋並選擇其他線上玩家 |
| 轉帳表單 | 輸入金額與貨幣，資料有誤時顯示原因 |
| 轉帳確認 | 對象、金額、手續費、實付合計、轉後餘額；按取消或關閉視窗即取消 |
| 兌換 | 只列有匯率的組合，表單顯示匯率與持有量，確認視窗顯示可得數量 |
| 排行榜 | 只有一種貨幣時略過選貨幣；自己的列加「（你）」，上方顯示名次 |
| 交易紀錄 | 分頁查看自己的紀錄，可依時間排序 |

清單可搜尋、排序及調整每頁數量，各清單分別記住設定，離線後清除。視窗元件已內建，無需安裝 tokaForms。

各指令加 `help` 可看說明，例如 `/pay help`。主控台 console 可用 `/balance <玩家>`、`/economy` 的管理子指令。

## 管理指令

`/economy` 的別名為 `/eco`，權限如下。

| 指令 | 說明 | 權限與預設 |
| --- | --- | --- |
| `/economy see [玩家] [貨幣]`、`/seemoney` | 同 `/balance`；不寫玩家看自己，console 需寫玩家 | 自己不限；看別人 `tokaEconomy.see`，預設 OP |
| `/economy give <玩家> <金額> [貨幣]`、`/givemoney` | 給予 | `tokaEconomy.admin`，預設 OP |
| `/economy take <玩家> <金額> [貨幣]`、`/takemoney` | 扣除 | `tokaEconomy.admin` |
| `/economy set <玩家> <金額> [貨幣]`、`/setmoney` | 設定餘額，可設為 0 | `tokaEconomy.admin` |
| `/economy ledger [玩家] [頁]` | 查詢交易紀錄，新的在前 | `tokaEconomy.admin` |
| `/economy rates` | 列出匯率 | `tokaEconomy.admin` |
| `/economy reload` | 重新讀取貨幣、手續費、匯率與清理設定 | `tokaEconomy.admin` |
| `/economy vacuum` | 立即在背景清理過期的交易紀錄 | `tokaEconomy.admin` |
| `/economy legacy list [頁]` | 列出尚未取回的舊名稱帳戶 | `tokaEconomy.admin` |
| `/economy legacy claim <名稱> <玩家>` | 把舊帳戶餘額併入指定玩家，並寫入交易紀錄 | `tokaEconomy.admin` |
| `/economy legacy drop <名稱>` | 刪除一筆舊帳戶，不給任何人 | `tokaEconomy.admin` |

`/bal`、`/money`、`/topmoney`、`/seemoney`、`/givemoney`、`/takemoney`、`/setmoney` 是啟動時另外註冊的名稱；已被其他插件使用的名稱會略過並寫入 log。`/seemoney`、`/givemoney`、`/takemoney`、`/setmoney` 受設定 `compat-commands` 控制。`tokaEconomy.fee.free` 讓擁有者轉帳不收手續費。

## 設定 config.yml

| key | 預設 | 說明 |
| --- | --- | --- |
| `language` | `zh-TW` | 語系，需重啟 |
| `help-lines-per-page` | `8` | help 每頁行數 |
| `top-lines-per-page` | `10` | 排行榜每頁行數 |
| `window-items-per-page` | `10` | 視窗清單每頁預設項目數 |
| `window-items-min`、`window-items-max` | `3`、`30` | 玩家可調的每頁項目數範圍 |
| `window-search-threshold` | `8` | 清單超過幾項才出現搜尋按鈕 |
| `window-icons` | `true` | 按鈕是否顯示圖示 |
| `window-rows-max` | `500` | 排行榜與交易紀錄視窗最多讀取的列數 |
| `compat-commands` | `true` | 是否註冊 `/seemoney`、`/givemoney`、`/takemoney`、`/setmoney`，需重啟 |
| `currencies.<代碼>` | `money`（主要）、`coin` | 代碼 `[a-z0-9]{2,16}`，建立後不可改；`primary` 恰好一種、`symbol`、`initial`（新帳戶起始額）、`fee-min`、`names`（語言對名稱，缺語言用 `language`，再缺用代碼） |
| `fee.rate` | `1` | 轉帳手續費百分比 |
| `fee.min` | `10` | 手續費最低額 |
| `fee-rules` | 空 | 手續費規則，見下 |
| `exchange.rates.<來源>.<目標>` | `money→coin 0.01`、`coin→money 90` | 匯率，所得 = 金額 × 匯率，捨去 |
| `ledger.keep-days` | `365` | 交易紀錄保留天數，0 不限 |
| `ledger.max-rows` | `200000` | 交易紀錄最多筆數，0 不限 |

手續費只在玩家對玩家的轉帳收取，系統加扣與兌換不收。實際費用 = max(最低額, 金額 × 費率) 無條件捨去，再套用規則折扣。

**手續費規則 `fee-rules`**：每條規則的條件須全部符合。

| 條件 | 說明 |
| --- | --- |
| `permission` | 轉出方擁有的權限節點 |
| `min-amount`、`max-amount` | 轉帳金額範圍 |
| `time` | 每日時段 `"HH:mm-HH:mm"`，可跨午夜 |
| `days` | 星期 `mon`…`sun` |
| `dates` | 日期期間 `{from: "2026-12-24", to: "2026-12-26"}`，含頭尾 |
| `count` | `{limit: 3, period: day}`：該週期內前 3 筆轉帳適用；`period` 為 `day`、`week`（週一起算）、`month`、`quarter`，或改用 `from`、`to`；次數由交易紀錄統計 |

效果擇一：`free: true` 或 `discount: 1–100`。同時符合多條時取最優惠的一條；加 `stack: true` 的規則會與之疊加，總折扣最多 100%。

## 側欄餘額 tokaDisplay

搭配 tokaDisplay 0.3.0 以上，可在側欄顯示各貨幣餘額。排列由 tokaDisplay 的 `sidebar.layout` 設定，`sidebar.show: false` 可關閉，`/economy reload` 會更新區段。

區段 owner 為 `tokaEconomy`，id 為貨幣代碼，預設 `money`、`coin`。文字範本位於 `lang/zh-TW.yml` 的 `ui.sidebar`。

## 舊名稱帳戶 legacy

舊帳戶以名稱識別，透過 `Economy.importLegacy` 匯入後保存在 `legacy` 資料集合，供玩家登入取回或由管理員指定。

- **自動取回**：玩家登入時，若有同名舊帳戶，名稱比對不分大小寫，且該 UUID 帳戶不存在或各貨幣餘額皆為 0，餘額併入該帳戶、刪除舊帳戶，並以 `legacy-claim` 寫入交易紀錄。取回時不再給新帳戶的起始餘額。
- **有爭議就不動**：該帳戶已有餘額，或另一個 UUID 帳戶使用同樣的名稱時，保留舊帳戶並在後台 log 記一筆；管理員用 `/economy legacy claim` 決定。
- 同名再次匯入會覆蓋；全部取回或刪除後不會再有新資料。登入時只查記憶體，不做磁碟存取。

## API

靜態入口 `dev.toka.economy.Economy`，於主執行緒呼叫。餘額操作使用記憶體資料；`resolveAsync` 會在背景查詢玩家檔，並回到主執行緒回呼。餘額、加扣、轉帳與兌換可傳入 `Player` 或 `UUID`；建立帳戶、設定餘額及預覽操作使用 `UUID`。

| 方法 | 說明 |
| --- | --- |
| `available()` | 經濟服務是否在運作 |
| `createAccount(UUID, String name)` | 建立帳戶（已存在則只更新名稱）；玩家登入時自動建立 |
| `hasAccount(UUID)` | 帳戶是否存在 |
| `resolve(String name)` | 名稱轉 UUID：線上玩家、已有帳戶者；不讀磁碟，tokaProfiles 尚未接入 |
| `resolveAsync(String name, Consumer<Optional<UUID>>)` | 同上，再加伺服器玩家檔（背景查詢，主執行緒回呼） |
| `nameOf(UUID)` | 顯示用名稱 |
| `balance(id, 貨幣)` | 餘額在 `Result.balance()` |
| `has(id, 貨幣, 金額)` | 餘額是否足夠 |
| `deposit(id, 貨幣, 金額, Cause)`、`withdraw(...)`、`set(UUID, ...)` | 加、扣、設定 |
| `transfer(from, to, 貨幣, 金額, Cause)` | 玩家對玩家轉帳，含手續費（`Result.fee()`） |
| `previewTransfer(from, to, 貨幣, 金額)` | 只算結果與手續費，不變更 |
| `exchange(id, 來源, 目標, 金額, Cause)`、`previewExchange(...)` | 兌換與預覽，所得在 `Result.received()` |
| `importLegacy(String name, Map<String,Long> balances, Cause)` | 匯入舊名稱帳戶（同名覆蓋，可重複執行；0 的金額略過）；失敗為 `INVALID_NAME`、`UNKNOWN_CURRENCY`、`INVALID_AMOUNT`、`OVERFLOW` |
| `legacyCount()` | 還沒被取回的舊帳戶數 |
| `currencies()`、`primary()` | 貨幣代碼清單（依設定順序）、主要貨幣 |
| `format(金額, 貨幣, 含符號)` | 千分位格式，如 `$12,345` |
| `currencyName(貨幣, 語言)` | 貨幣名稱 |

`Result(status, amount, fee, balance, received)` 的 `status` 為 `Status`：`OK`、`INVALID_AMOUNT`、`UNKNOWN_CURRENCY`、`NO_ACCOUNT`、`INSUFFICIENT_FUNDS`、`OVERFLOW`、`SAME_ACCOUNT`、`NO_RATE`、`INVALID_NAME`、`NO_LEGACY`、`UNAVAILABLE`。`Cause.of("原因代碼", "插件名")` 會寫進交易紀錄。轉帳由轉出方付「金額 + 手續費」，對方收到完整金額。

```java
Result r = Economy.transfer(from, to, "money", 500, Cause.of("shop-buy", "myPlugin"));
if (!r.ok()) { /* r.status() */ }
```

## 版本紀錄

- 0.3.1：向 tokaDisplay 註冊側欄餘額區段（每種貨幣一列），新增 `sidebar.show`。
- 0.3.2：後台記錄套用顏色（說明文字 §b、數字 §a、警告 §e、錯誤 §c）。
- 0.3.0：視窗元件（Menu、ListMenu、Confirm 等）內建於 `dev.toka.economy.form`，移除 tokaForms 相依；排行榜與交易紀錄的排序／搜尋偏好各自獨立，交易紀錄「舊到新」改依時間與編號排序；新增舊名稱帳戶（`Economy.importLegacy`、登入自動取回、`/economy legacy`）。
- 0.2.0：`/balance` 玩家視窗（錢包、轉帳、兌換、排行榜、交易紀錄），使用 tokaForms 0.1.1（0.3.0 起內建）；新增 `window-*` 設定，`/economy reload` 一併更新。
- 0.1.0：初版。多貨幣餘額、API、轉帳手續費與規則、兌換、排行榜、交易紀錄與清理、管理與相容指令。

授權：MIT。
