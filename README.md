# SyncVerse - Bài 7.1

Ứng dụng đồng bộ file nội bộ siêu nhẹ gồm Central Server RESTful và Client CLI. Client không mở webserver hoặc port; toàn bộ giao tiếp đi theo chiều client chủ động gọi HTTP tới server.

## Yêu cầu đã thực hiện

- Hỗ trợ 5 message type: `HELLO`, `HEARTBEAT`, `FILE_CHANGE`, `RECONNECT`, `DELTA_REQUEST`.
- Client không mở port hoặc webserver.
- Theo dõi thư mục bằng `WatchService`.
- Đồng bộ tạo, sửa và xóa file.
- Thư mục đồng bộ dạng phẳng, không xử lý thư mục con.
- Giới hạn mỗi file tối đa 1 MB.
- Client polling delta mỗi 2 giây.
- Client gửi heartbeat mỗi 4 giây.
- Client offline rồi chạy lại sẽ gửi `RECONNECT` và lấy phần delta bị bỏ lỡ.
- Server lưu journal tại `server-data/journal.tsv`, nên vẫn giữ lịch sử sau khi restart.
- Chính sách xung đột: thay đổi đến server sau cùng sẽ thắng (`last write wins`).
- Maven build tạo đúng hai file thực thi: `target/server.jar` và `target/client.jar`.

## Công nghệ

- Java 17
- Maven
- JDK `HttpServer`
- HTTP RESTful
- `WatchService`
- Polling
- File journal và properties
- SHA-256 để nhận biết thay đổi nội dung file

## Build

Chạy tại thư mục gốc có `pom.xml`:

```bash
mvn clean package
```

Kết quả:

```text
target/server.jar
target/client.jar
```

## Chạy server

```bash
java -jar target/server.jar AlphaServer
```

## Chạy client

Terminal Alice:

```bash
java -jar target/client.jar Alice_Node ./workspace_alice
```

Terminal Bob:

```bash
java -jar target/client.jar Bob_Node ./workspace_bob
```

Terminal Charlie:

```bash
java -jar target/client.jar Charlie_Node ./workspace_charlie
```

Có thể đổi URL server bằng biến môi trường.

### Linux/macOS

```bash
SYNCVERSE_SERVER_URL=http://localhost:8080 java -jar target/client.jar Alice_Node ./workspace_alice
```

### PowerShell

```powershell
$env:SYNCVERSE_SERVER_URL = "http://localhost:8080"
java -jar target/client.jar Alice_Node ./workspace_alice
```

## API chính

- `POST /api/register`: xử lý `HELLO`, đăng ký client và trả client ID.
- `POST /api/heartbeat`: xử lý `HEARTBEAT`, duy trì trạng thái phiên.
- `POST /api/change`: nhận `FILE_CHANGE` gồm `CREATE`, `UPDATE`, `DELETE`.
- `POST /api/reconnect`: nhận `RECONNECT` cùng version cuối client đã biết.
- `GET /api/delta`: xử lý `DELTA_REQUEST`, trả các thay đổi có version lớn hơn version client đang giữ.
- `GET /health`: kiểm tra trạng thái server.

## Luồng tổng thể

```text
Client khởi động
→ gửi HELLO
→ gửi RECONNECT nếu đã có state cũ
→ gửi DELTA_REQUEST để lấy phần bị bỏ lỡ
→ WatchService theo dõi file local
→ gửi FILE_CHANGE khi tạo/sửa/xóa
→ polling DELTA_REQUEST mỗi 2 giây
→ gửi HEARTBEAT mỗi 4 giây
```

## Demo 1: Alice tạo file, Bob tự nhận

1. Chạy server, Alice và Bob.
2. Tạo file:

```text
workspace_alice/config.txt
```

3. Ghi nội dung:

```text
hello from alice
```

4. Sau khoảng 2–4 giây, file tự xuất hiện tại:

```text
workspace_bob/config.txt
```

## Demo 2: Bob sửa file, Alice tự cập nhật

1. Mở:

```text
workspace_bob/config.txt
```

2. Đổi nội dung thành:

```text
hello from bob
```

3. Sau khoảng 2–4 giây, nội dung trong `workspace_alice/config.txt` tự cập nhật theo.

## Demo 3: Bob xóa file, Alice xóa theo

1. Xóa:

```text
workspace_bob/config.txt
```

2. Bob gửi `FILE_CHANGE DELETE` lên server.
3. Sau vài giây, file tương ứng trong `workspace_alice` tự biến mất.

## Demo 4: Alice offline rồi nhận delta bị bỏ lỡ

1. Dừng riêng Alice bằng `Ctrl + C`.
2. Giữ server và Bob đang chạy.
3. Tạo trong workspace Bob:

```text
workspace_bob/offline.txt
```

4. Nội dung:

```text
created while alice was offline
```

5. Chạy lại Alice bằng cùng client name và workspace:

```bash
java -jar target/client.jar Alice_Node ./workspace_alice
```

6. Alice gửi `RECONNECT` với version cuối đã lưu.
7. Server trả delta còn thiếu.
8. `offline.txt` tự xuất hiện trong `workspace_alice`.

## Dữ liệu nội bộ

- `server-data/journal.tsv`: lịch sử thay đổi và global version trên server.
- `client-state/<ClientName>.properties`: version cuối và trạng thái file của từng client.
- `workspace_alice/`, `workspace_bob/`, `workspace_charlie/`: thư mục đồng bộ của từng client.
- Các dữ liệu runtime như `server-data/` và `client-state/` được bỏ qua trong Git.

## Quy tắc đồng bộ

- Chỉ đồng bộ file ở cấp trực tiếp của workspace.
- Không đồng bộ thư mục con.
- File lớn hơn 1 MB bị từ chối.
- Thay đổi đến server sau cùng sẽ thắng.
- Client không đẩy ngược file vừa nhận từ server để tránh vòng lặp đồng bộ.
- Mỗi client lưu version cuối để có thể reconnect và lấy phần bị bỏ lỡ.

## Kết quả đã kiểm tra

- Maven build: `BUILD SUCCESS`.
- Tạo thành công `server.jar` và `client.jar`.
- Alice tạo file, Bob nhận đúng.
- Bob sửa file, Alice cập nhật đúng.
- Bob xóa file, Alice xóa theo.
- Alice offline, Bob tạo file mới, Alice mở lại và nhận đúng delta bị bỏ lỡ.
- Client không mở port; toàn bộ request đi từ client tới server.
