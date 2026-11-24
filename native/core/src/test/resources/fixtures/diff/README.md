# Kiểm tra vi sai Dart ↔ Kotlin cho client X

Mục đích: bắt chỗ bản Kotlin (`native/core/.../core/client`) port sai so với bản
Flutter (`lib/core/client/twitter_client.dart`) mà không cần gọi X thật. Cùng một
đầu vào chạy qua cả hai bản, đầu ra phải giống nhau.

Không có dữ liệu thật của X ở đây: response là tự soạn theo hình dạng GraphQL của X,
giá trị bịa.

## Thư mục

- `responses/` — 47 response tự soạn (SearchTimeline, UserTweets, TweetDetail,
  HomeTimeline/VideoMixer, Following, UserByScreenName). Tiền tố tên file quyết định
  nó đi qua hàm nào: `search_`, `usertweets_`, `detail_`, `home_`, `following_`,
  `profile_`. Sinh bằng `tools/gen_fixtures.py`.
- `golden_dart/uri_golden.json` — **golden từ client Dart**: mọi request bản Flutter
  gửi cho 1.417 bộ tham số (địa chỉ đầy đủ đã mã hoá + body của POST). Phần
  `features`/`fieldToggles` dài và lặp lại nên lưu một lần trong `blobs`.
  Ca có `"requests": []` là ca bản Flutter KHÔNG gửi gì (xem "Lỗi của bản Dart").
- `golden_dart/parse_golden.json` — **golden từ parser Dart**: kết quả bản Flutter
  đọc từng file trong `responses/` (danh sách post với mọi trường của model,
  cursorTop/cursorBottom; danh sách subscription; profile).
- `kotlin_created_at.json` — KHÔNG phải golden từ Dart. `createdAtMillis` theo bản
  Kotlin, chỉ dùng ở chỗ Dart ra null (xem dưới).
- `tools/zz_diff_dump_test.dart.txt` — test Flutter sinh ra hai file golden. Nó chạy
  `TwitterClient` thật của `lib/` sau một HTTP client giả (`http.runWithClient` +
  `MockClient`), không sửa gì trong `lib/`, không ra mạng.

Test Kotlin: `DartGoldenUriTest`, `DartGoldenParseTest`
(`native/core/src/test/kotlin/.../core/client/`).

## Sinh lại golden

Từ gốc repo:

    python3 native/core/src/test/resources/fixtures/diff/tools/gen_fixtures.py   # nếu sửa response
    cp native/core/src/test/resources/fixtures/diff/tools/zz_diff_dump_test.dart.txt test/zz_diff_dump_test.dart
    flutter test test/zz_diff_dump_test.dart
    cp /tmp/xflow-diff/out/*.json native/core/src/test/resources/fixtures/diff/golden_dart/
    rm test/zz_diff_dump_test.dart
    # nếu số post/thứ tự đổi: ghi lại ngày theo bản Kotlin
    XFLOW_RECORD_DATES=$PWD/native/core/src/test/resources/fixtures/diff/kotlin_created_at.json \
        native/tools/check-core.sh DartGoldenParse

## Khác biệt đã biết (cố ý giữ)

- `createdAt`: bản Dart đọc ngày kiểu X (`Wed Oct 10 20:19:24 +0000 2018`) ra **null**
  (DateFormat của intl không parse được múi giờ `Z`; rơi xuống `DateTime.tryParse`
  cũng không đọc được). Bản Kotlin đọc được. Ngày ISO và `created_at_ms` thì hai bản
  giống nhau.
- `viewCount`: Dart là số 64 bit, model Kotlin là `Int` nên dừng ở 2.147.483.647.

## Lỗi của bản Dart (Kotlin không theo)

- Bản Flutter không bao giờ gửi được request có ký tự ngoài ASCII hoặc dấu `%` lẻ
  trong địa chỉ (tìm "tiếng Việt", "100% real", hashtag tiếng Nhật…): lúc ghi log nó
  gọi `Uri.decodeQueryComponent` lên giá trị đã giải mã, hàm này ném lỗi, request bị
  bỏ và kết quả là rỗng. Bản Kotlin ghi log nguyên văn khi không giải mã được và vẫn
  gửi request. Phần mã hoá của các chuỗi này vẫn được so qua các ca `"op": "encode"`
  (chạy thẳng `jsonEncode` + `Uri.https` của Dart).
