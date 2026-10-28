# GitHub 發佈說明

目標 repository：<https://github.com/Betan423/PriveMVP>

## 已排除的本機內容

GitHub 版本不包含：

- 本機 JDK（`.tools`）
- Android／Gradle／IDE 建置快取
- `local.properties` 與任何 keystore
- Receiver 實際收到的手機資料
- APK／AAB 與壓縮檔
- Python cache 與文件 QA 暫存圖

APK 會由 GitHub Actions 建置並作為 workflow artifact 提供，不直接提交二進位檔。

## 第一次推送

在乾淨的發佈目錄執行：

```powershell
git status
git remote -v
git push -u origin main
```

如果遠端 repository 已經有 GitHub 建立的 README 或其他 commit，先整合遠端：

```powershell
git fetch origin
git pull --rebase origin main
git push -u origin main
```

若 rebase 顯示內容衝突，先停止，不要使用 force push：

```powershell
git rebase --abort
```

## 後續更新

```powershell
git add -A
git status
git commit -m "Describe the change"
git push
```

推送後可在 GitHub 的 Actions 頁面查看 Receiver tests、Android tests、Lint 與 APK build 結果。
