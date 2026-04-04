# Release Checklist

APK 릴리즈 전 반드시 확인할 항목들.

## 빌드
- [ ] 올바른 `versionName` / `versionCode` 설정 확인
- [ ] Debug 또는 서명된 Release APK 사용 (**unsigned APK 절대 업로드 금지**)
- [ ] 파일명에 `unsigned` 포함 시 업로드하지 말 것

## 서명 확인
```bash
apksigner verify --print-certs <apk파일>
```
- [ ] 위 명령어 실행 시 에러 없이 인증서 정보 출력되는지 확인
- [ ] `DOES NOT VERIFY` 나오면 업로드 금지

## GitHub Release
- [ ] 태그 버전과 APK versionName 일치 확인
- [ ] Release notes 작성
- [ ] APK 업로드 후 다운로드 받아서 서명 재확인
