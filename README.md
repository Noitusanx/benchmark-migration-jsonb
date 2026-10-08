# Lab migrasi JSONB user: selective upsert dengan Java

Lab ini membandingkan **JDBC, MyBatis, Spring Data JPA Repository, dan EntityManager**.
Sumbernya tabel `lab_java.m_user` dengan tiga kolom JSONB: `"group"`, `coupons`, dan
`benefit`. Java membaca JSON lalu mengubah setiap item menjadi satu baris target.
Pembaca JDBC dan Transformer Jackson sama untuk semua metode; yang berbeda adalah
strategi membaca target dan menulisnya. JSON sumber tetap ada.

**Hanya untuk database lokal khusus lab.** Setiap awal benchmark membuat ulang dummy
serta mengosongkan empat tabel lab. Setiap trial mereset tiga tabel target lalu menyiapkan
kondisi awal sesuai skenario. Program menolak nama database selain `jsonb_migration_lab`,
tetapi pemeriksaan nama bukan jaminan bahwa database aman: jangan gunakan database kerja/client.
Tidak ada scheduler, master `m_coupon`, sinkronisasi perubahan, atau bukti zero downtime.

## 1. Aturan upsert

- Key belum ada: insert semua kolom.
- Key sudah ada: update **hanya kolom non-key yang nilainya berbeda**.
- Semua nilai sama: tidak melakukan UPDATE.
- Nilai `null` mengikuti sumber, hanya untuk kolom yang boleh null (`file_name`, `update_date`).
- Item yang hilang dari JSON tidak dihapus dari target.
- Angka desimal dibandingkan berdasarkan nilai, bukan jumlah digit desimal.
- Timestamp dibandingkan berdasarkan waktu aktual, bukan tulisan offset zona waktu.

Key adalah identitas baris; non-key adalah detailnya:

| Tabel target | Primary key | Kolom non-key |
| --- | --- | --- |
| `user_reward_group` | `(user_id,reward_group_id)` | file_name, period_start, period_end |
| `user_coupon` | `(user_id,coupon_id)` | reward_group_id, coupon_code, quota_used, usage_daily, usage_weekly, usage_monthly, update_date |
| `user_benefit` | `(user_id,period_type)` | amount, end_date, last_update |

Jika key berubah, itu dianggap baris baru. Baris dengan key lama tidak otomatis dihapus.
Contoh: JSON awal memiliki kupon A dan B, lalu sumber hanya memiliki A. Upsert memperbarui A,
tetapi B tetap di target. Ini **bukan sinkronisasi lengkap dengan delete**.

**Implementasi ini read-before-write untuk satu writer:** baca target per chunk, bandingkan,
lalu insert/update. Ini bukan atomic native `ON CONFLICT` yang tahan persaingan writer.
Jangan memakai kode ini untuk beberapa writer produksi tanpa desain concurrency tambahan;
writer lain dapat memasukkan key setelah lookup, atau mengubah nilai yang sedang dibandingkan.

## 2. Jalankan dengan Docker

Kebutuhan: Docker Desktop dan Docker Compose. Java 17 dan Maven tersedia di image build.
Build pertama memerlukan internet. Dari folder yang berisi `compose.yaml`:

```bash
docker compose up -d --wait postgres
docker compose build benchmark
```

Coba semua metode dan semua skenario dengan 1.000 user, satu trial terukur, tanpa warmup:

```bash
docker compose run --rm -e BENCH_USERS=1000 -e SCENARIOS=insert,update,mixed,unchanged -e WARMUPS=0 -e REPETITIONS=1 benchmark
```

Ini smoke test, **bukan** hasil final untuk menentukan metode tercepat.
Tunggu sampai semua baris `PASS` dan muncul `Finished`.
`--rm` hanya menghapus container benchmark sementara setelah selesai, bukan image,
container PostgreSQL, volume database, atau CSV di Mac.

Baseline yang lebih baik untuk 1.000 user:

```bash
docker compose run --rm -e BENCH_USERS=1000 -e WARMUPS=1 -e REPETITIONS=5 benchmark
```

Default penuh (1.000 / 5.000 / 10.000 user):

```bash
docker compose run --rm benchmark
```

Dengan empat metode, empat skenario, satu warmup dan lima pengukuran:
`3 × 4 × 4 × (1 + 5) = 288` migrasi lengkap. Jalankan dataset kecil dahulu.
Metode berjalan bergantian, bukan paralel; urutan metode diacak deterministik tiap putaran.

**CSV dalam `results` ditimpa setiap benchmark dimulai.** Simpan dulu:

```bash
cp -R results "results-backup-$(date +%Y%m%d-%H%M%S)"
```

### Custom: JDBC saja, insert, 5.000 user

Salin perintah berikut sebagai **satu baris**. Opsi `-e` langsung menentukan environment
variable di container, sehingga tidak bergantung pada variabel shell yang belum di-export.

```bash
docker compose run --rm -e BENCH_USERS=5000 -e METHODS=jdbc -e SCENARIOS=insert -e WARMUPS=1 -e REPETITIONS=5 benchmark
```

Output awal harus menyebut `Dummy ready: 5,000 users`, lalu hanya metode `jdbc` dan
skenario `insert`. Ingat: ini insert melalui writer upsert, bukan insert-only tanpa lookup.

### Simpan setiap run ke folder berbeda (disarankan)

Buat nama folder baru setiap percobaan agar hasil tidak tertimpa:

```bash
run="jdbc-insert-5000-$(date +%Y%m%d-%H%M%S)"
docker compose run --rm -e OUTPUT_DIR="/app/results/$run" -e BENCH_USERS=5000 -e METHODS=jdbc -e SCENARIOS=insert -e WARMUPS=1 -e REPETITIONS=5 benchmark
```

Folder `/app/results` di container terhubung ke `./results` di komputer. File otomatis
tersimpan di `results/$run/`, berisi summary.csv, runs.csv, batches.csv, environment.txt.

```bash
column -s, -t < "results/$run/summary.csv"
```

Ini menyimpan **file pengukuran**, bukan snapshot database. Tabel lab tetap direset.
Jangan memakai nama folder yang sama untuk run berikutnya jika ingin menjaga hasil lama.

Jika run gagal, jangan menggunakan summary lama. Runner menghapus summary lama sebelum setup.
Hasil insert-only versi sebelumnya disimpan secara lokal di `archives/insert-before-upsert-*`
ketika implementasi upsert dilakukan; folder tersebut tidak disertakan ke GitHub.
Jangan gabungkan CSV lama tanpa kolom scenario dengan CSV baru.

### File yang disertakan ke GitHub

`.gitignore` mengecualikan target build, hasil/backup benchmark, archives, konfigurasi IDE,
file OS, log, dan file credential lokal. `results/.gitkeep` dipertahankan agar folder output
tersedia setelah clone. Source code, SQL, Docker, pom.xml, README, dan diagram di docs tetap
bisa dimasukkan ke repository.

Sebelum commit/push, periksa `git status --short` dan pastikan tidak ada data asli atau
credential produksi. `.gitignore` tidak menghapus file yang sudah tracked, tidak membersihkan
history Git, dan tidak menyembunyikan secret yang ditulis di file source/config biasa.
Konfigurasi database dalam compose adalah khusus lab, bukan credential produksi.

## 3. Empat skenario, bukan empat aturan bisnis

| `scenario` | Kondisi awal target | Pekerjaan selama migrasi |
| --- | --- | --- |
| `insert` | Kosong | Semua item di-insert |
| `update` | Semua key ada, nilai sengaja berbeda | Semua item di-update |
| `mixed` | User pertama sampai `floor(users/2)` ada dengan nilai berbeda; sisanya belum ada | Campuran update dan insert |
| `unchanged` | Semua data sudah sama dengan sumber | Lookup dan perbandingan saja; tidak ada insert/update |

Semua skenario menggunakan **writer upsert yang sama**. Label `insert` berarti kondisi awal
kosong, bukan kembali menggunakan implementasi insert-only lama tanpa lookup.
Karena itu hasil baru tidak otomatis setara dengan baseline kode lama.

Sebelum **setiap** trial, target dikosongkan dan disiapkan lewat proyeksi SQL yang sama,
bukan melalui writer salah satu metode. Sumber tidak berubah selama rangkaian trial.
Untuk skenario update/mixed, baseline target dibuat berbeda secara deterministik:
- Grup: file_name berbeda, termasuk kasus kembali ke null.
- Kupon: quota_used berbeda dan update_date dibalik null/non-null.
- Benefit: amount berbeda.
Kolom lainnya tetap sama sehingga benar-benar menguji update sebagian kolom.
Semua metode menerima kondisi yang identik. Setup target ini tidak masuk waktu migrasi.

Pilih skenario atau metode tertentu:

```bash
docker compose run --rm -e BENCH_USERS=1000 -e SCENARIOS=update -e METHODS=jdbc,mybatis -e WARMUPS=1 -e REPETITIONS=5 benchmark
```

## 4. Data dummy dan bentuk hasil

`seed.sql` membuat 6 grup, 10 kupon, dan 3 benefit per user, total 19 item.
Termasuk nilai null, updateDate yang tidak ada, tanggal tahun 9999, offset `+07:00`,
ID string dan UUID, serta benefit desimal. Distribusi ini masih perlu disesuaikan data nyata.

| User sumber | Grup | Kupon | Benefit | Total item/baris target |
| ---: | ---: | ---: | ---: | ---: |
| 1.000 | 6.000 | 10.000 | 3.000 | 19.000 |
| 5.000 | 30.000 | 50.000 | 15.000 | 95.000 |
| 10.000 | 60.000 | 100.000 | 30.000 | 190.000 |

Jika dataset `1000,5000,10000`, sumber dibuat 10.000 user **sekali**. Tiap ukuran menguji
subset user pertama. Semua target punya foreign key ke sumber. Tidak ada master grup/kupon.
Counter merupakan kondisi pemakaian saat ini, bukan daftar history transaksi.

Contoh alur satu user:

```text
m_user U00000001
  JSON group:   6 item → 6 baris user_reward_group
  JSON coupons: 10 item → 10 baris user_coupon
  JSON benefit: 3 item → 3 baris user_benefit
```

Setiap baris target diberi user_id dan key item sebagai identitas.
Tabel target dibuat saat setup oleh schema.sql, bukan dibuat ulang setiap batch.

## 5. Flow kode: mulai dari sini

1. `Application.java`: start Spring Boot command-line, bukan REST API.
2. `BenchmarkRunner.java`: membaca parameter, setup sumber, memilih skenario/metode,
   warmup/pengulangan, menyiapkan target, memvalidasi, dan menyimpan CSV.
3. `BenchmarkService.java`: membaca user per batch, mengukur migrasi, serta validasi SQL.
4. `Transformer.java`: parsing tiga JSON dan menghasilkan `Model.Rows`.
5. `Writers.java`: menjalankan lookup dan penulisan sesuai metode.
6. `SqlUpsert.java`: definisi tabel/kolom dan perbandingan nilai untuk JDBC/MyBatis;
   menghasilkan INSERT atau UPDATE dengan SET hanya untuk kolom yang berbeda.
7. `*Entity.java`, `RowId.java`, `*Repository.java`: mapping JPA dan lookup bulk berdasarkan key.

Per batch user:

```text
Baca sumber → transformasi JSON → lookup target per chunk
→ bandingkan kolom → insert/update/skip → flush/executeBatch → commit
```

- JDBC: PreparedStatement SELECT, batch INSERT/UPDATE, lalu commit.
  Perubahan dengan kombinasi kolom yang sama dikelompokkan dalam SQL batch.
- MyBatis: mapper SQL provider, SELECT lalu ExecutorType.BATCH dan flushStatements.
- Repository: lookup bulk via repository, ubah entity existing, saveAll untuk data
  baru/berubah, flush dan clear.
- EntityManager: lookup bulk JPQL, persist entity baru, ubah managed entity existing,
  flush dan clear. Tidak menggunakan native SQL upsert.
- JPA menggunakan `@DynamicUpdate` agar SQL UPDATE hanya memuat kolom dirty.
  Entity dengan ID assigned hanya dianggap baru jika lookup tidak menemukan key.

Seluruh penulisan satu batch user berada dalam satu transaksi. Error membatalkan insert
**dan** update batch itu; batch terdahulu yang sudah commit tetap ada. Belum ada resume.
Pembacaan sumber berada di luar transaksi penulisan; sumber diasumsikan tetap.

## 6. Parameter dan dua ukuran batch

| Parameter | Default | Arti |
| --- | --- | --- |
| BENCH_USERS | 1000,5000,10000 | Dataset user, maksimum 100.000 |
| METHODS | jdbc,mybatis,jpa_repository,entity_manager | Metode yang diuji |
| SCENARIOS | insert,update,mixed,unchanged | Kondisi awal target |
| USER_BATCH_SIZE | 500 | Maksimal user dibaca/ditransformasi dan hasilnya di-commit bersama |
| WRITE_BATCH_SIZE | 100 | Maksimal item dalam chunk lookup dan pengiriman/flush |
| WARMUPS | 1 | Trial pemanasan per kombinasi, dikeluarkan dari summary |
| REPETITIONS | 5 | Trial terukur per kombinasi |
| OUTPUT_DIR | /app/results di Docker; results saat lokal | Folder CSV; gunakan subfolder berbeda untuk menyimpan setiap run |

500 user menghasilkan 9.500 item target. Diproses dalam chunk maksimal 100 item per jenis
untuk lookup dan penulisan, tetapi hanya satu commit setelah ketiga jenis selesai.
SQL UPDATE dengan kolom berbeda dapat terpecah menjadi batch lebih kecil.
**Flush bukan commit.** Clear melepas managed entity, bukan seluruh data hasil transformasi.

`application.properties` mengatur datasource dan Hibernate. `compose.yaml` meneruskan
parameter benchmark serta DB_URL ke container. `Writers` mengatur MyBatis secara Java,
bukan XML. `pom.xml` menentukan dependency. Driver memakai `reWriteBatchedInserts=true`;
ini optimasi batch INSERT, bukan optimasi otomatis untuk semua UPDATE.

## 7. Waktu dan file CSV

| File | Kegunaan |
| --- | --- |
| summary.csv | Median/min/max tiap scenario, method, dan jumlah user; warmup dikeluarkan |
| runs.csv | Satu baris untuk satu migrasi lengkap, termasuk warmup |
| batches.csv | Detail rentang user dan durasi setiap batch |
| environment.txt | Versi Java/PostgreSQL, CPU JVM, heap, konfigurasi dan strategi penulisan |

Buka ringkasan:

```bash
column -s, -t < results/summary.csv
```

Bandingkan **scenario dan jumlah user yang sama**. Jangan membandingkan insert 1.000 user
dengan update 10.000 user sebagai penentu metode tercepat.

- `median_total_ms`: median durasi migrasi lengkap. Lebih kecil lebih cepat.
- `median_write_commit_ms`: median total fase lookup target + penulisan + commit per trial.
  Median ini dihitung terpisah dari median total dan bisa berasal dari trial berbeda.
- `median_users_per_second`: `source_users × 1000 / median_total_ms`; lebih besar lebih cepat.
  Ini user sumber per detik, bukan baris target. Bukan median throughput yang dihitung terpisah.
- `min_total_ms` / `max_total_ms`: variasi antartrial.
- `warmup=true` di runs: pemanasan, bukan pengukuran untuk summary. Nomor repetition
  warmup dan trial terukur dihitung terpisah.
- `group_rows`, `coupon_rows`, `benefit_rows`: **item yang diproses**, bukan jumlah SQL
  INSERT/UPDATE. Pada unchanged, item tetap diproses tetapi tidak ditulis ulang.
- `json_text_bytes` di runs: ukuran teks tiga JSON sumber, bukan ukuran disk database.
- `validation=PASS`: pemeriksaan berhasil; pada unchanged juga tidak ada perubahan row version.

Pengukuran memakai selisih `System.nanoTime()` dibagi 1.000.000 menjadi milidetik.
`total_ms` mencakup seluruh pembacaan sumber, parsing/transformasi, lookup target,
insert/update, flush, commit, dan overhead loop. Termasuk pembacaan terakhir yang kosong.
`read_ms` / `transform_ms` / `write_commit_ms` merupakan jumlah fase selama satu migrasi.
Jumlah ketiganya bisa sedikit berbeda dari total karena overhead loop/pencatatan di memori.

**Tidak termasuk:** build, startup Spring Boot, setup sumber/target, ANALYZE, validasi,
serta penulisan CSV/console. Total bukan waktu dari mengetik perintah Docker sampai selesai.

## 8. Validasi dan test

SQL independen memakai jsonb_each serta dua arah EXCEPT ALL untuk memeriksa semua nilai
pada key yang ada di sumber. Item yang hilang boleh tetap ada di target sesuai aturan no-delete.
Untuk fixture benchmark, yang tidak memiliki item hilang, jumlah total baris juga harus tepat.
Unchanged membandingkan fingerprint key dan PostgreSQL xmin sebelum/sesudah trial,
di luar timer, untuk mendeteksi UPDATE fisik walaupun nilainya tidak berubah.

Build menjalankan unit test Transformer. Integration test memeriksa empat writer:
insert/update/campuran/unchanged, null, seluruh kolom non-key, item hilang, timestamp/decimal
ekuivalen, validasi yang mendeteksi nilai salah, dan rollback karena foreign key tidak valid.
Trigger khusus test membuktikan bahwa kolom yang tidak berubah tidak muncul dalam SET.
Trigger tersebut dibersihkan setelah test, bukan dipakai benchmark.

```bash
docker compose run --rm tests
```

Test **mereset lab**. Jangan jalankan test dan benchmark bersamaan. Jalankan benchmark lagi
setelah test jika ingin melihat hasil akhir yang sesuai CSV di DataGrip.

Alternatif lokal dengan Java 17 + Maven:

```bash
docker compose up -d --wait postgres
mvn test
RUN_DB_TESTS=true mvn test
mvn package
BENCH_USERS=1000 SCENARIOS=insert,update,mixed,unchanged WARMUPS=0 REPETITIONS=1 \
  java -jar target/user-jsonb-benchmark-1.0.0.jar
```

## 9. DataGrip dan troubleshooting

Koneksi dari Mac: host localhost, port 55434, database jsonb_migration_lab.
Username/password lokal ada di compose.yaml. Schema tabel **lab_java**, bukan public.
Java dalam Docker menggunakan `postgres:5432`, bukan `localhost:55434`.

```sql
SELECT id,"group",coupons,benefit FROM lab_java.m_user WHERE id='U00000001';
SELECT * FROM lab_java.user_coupon WHERE user_id='U00000001' ORDER BY coupon_id;
```

SQL pemeriksaan:

```bash
docker compose exec -T postgres psql -U lab -d jsonb_migration_lab -v ON_ERROR_STOP=1 < 01_inspect.sql
docker compose exec -T postgres psql -U lab -d jsonb_migration_lab -v ON_ERROR_STOP=1 < 02_report.sql
```

Database mempertahankan **hasil trial terakhir**, bukan riwayat semua metode/skenario.
CSV menyimpan riwayat pengukuran. docker compose up hanya menyiapkan PostgreSQL;
tabel dan dummy dibuat ketika benchmark/test dijalankan.

```bash
docker compose ps
docker compose logs postgres
docker compose port postgres 5432
```

Jika port bentrok, ubah sisi kiri mapping port di compose dan sesuaikan GUI/DB_URL Java lokal.
Jika build/test gagal, jangan mengabaikan error untuk menarik kesimpulan performa.
Transformer melaporkan ID user dan alasan ketika data sumber tidak valid.

Berhenti tanpa menghapus database: `docker compose down`.
`docker compose down -v` menghapus volume/data lab: gunakan hanya jika memang ingin membuangnya.

Referensi resmi:
- https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html
- https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#dynamic-update
- https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#batch
- https://mybatis.org/mybatis-3/java-api.html
- https://jdbc.postgresql.org/documentation/use/
- https://www.postgresql.org/docs/17/functions-json.html
# benchmark-migration-jsonb
