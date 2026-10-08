# Implementation Report — selective upsert

Pemeriksaan dilakukan pada 4 Oktober 2026. Dokumen ini menggantikan status paket awal
sebelum build/test PostgreSQL tersedia. Tidak ada commit, push, atau pull request.

## 1. Approved plan used

Pengguna menyetujui aturan berikut, lalu memberi instruksi `lanjut implementasi`:

- Empat metode mendukung insert untuk key baru.
- Key existing diperbarui hanya pada kolom non-key yang nilainya berbeda.
- Nilai sama tidak menghasilkan UPDATE.
- Null mengikuti sumber pada kolom nullable.
- Item yang hilang dari JSON tidak dihapus.
- Benchmark memiliki skenario insert, update, mixed, dan unchanged dengan baseline identik.
- Test dan dokumentasi diperbarui; hasil lama tidak dicampur dengan hasil baru.

Project adalah Java 17 / Spring Boot / PostgreSQL. Tidak ditemukan instruksi standar
khusus repository; pola existing dan perintah Maven/Docker dalam README digunakan.

## 2. Files changed

Kode produksi:

- `src/main/java/lab/Writers.java`
- `src/main/java/lab/SqlUpsert.java` (baru)
- `src/main/java/lab/BenchmarkService.java`
- `src/main/java/lab/BenchmarkRunner.java`
- `src/main/java/lab/GroupEntity.java`
- `src/main/java/lab/CouponEntity.java`
- `src/main/java/lab/BenefitEntity.java`
- `src/main/java/lab/GroupRepository.java`
- `src/main/java/lab/CouponRepository.java`
- `src/main/java/lab/BenefitRepository.java`

Test/config/dokumentasi:

- `src/test/java/lab/BenchmarkIntegrationTest.java`
- `compose.yaml`
- `README.md`
- `VALIDATION.md`

Tidak mengubah dependency, schema tabel, seed sumber, Transformer, Model, RowId,
Application, unit test Transformer, Dockerfile, atau script pemeriksaan SQL.

## 3. Changes by file

- **Writers:** lookup existing keys dalam chunk lalu insert/update/skip; semua penulisan
  satu batch user tetap transactional. JDBC memakai PreparedStatement dan grouping SQL
  berdasarkan kolom yang berubah. MyBatis menjalankan mapper SQL provider dalam BATCH.
  Dua metode JPA memakai bulk lookup, entity baru untuk key yang belum ada, dan dirty
  checking untuk entity existing.
- **SqlUpsert:** metadata tiga tabel, perbandingan decimal/time/null, serta pembentukan
  parameterized INSERT/UPDATE/SELECT untuk JDBC dan MyBatis. SET hanya memuat kolom berbeda.
- **Entities:** updateFrom membandingkan setiap non-key; DynamicUpdate membatasi SQL SET.
- **Repositories:** bulk query composite keys untuk menghindari lookup per item.
- **Service:** baseline target SQL per skenario; update/mixed dibuat stale secara
  deterministik, sumber tetap. Validasi key sumber lewat SQL independen; item hilang
  boleh tetap ada. Fixture benchmark juga diperiksa exact counts. Fingerprint key/xmin
  mendeteksi physical UPDATE dalam skenario unchanged.
- **Runner:** parameter SCENARIOS dan label scenario pada tiga CSV, grouping median per
  scenario/method/user count, serta validasi unchanged. Lookup target masuk fase
  write_commit_ms; persiapan baseline dan validasi tetap di luar timer.
- **Compose:** meneruskan SCENARIOS dengan default keempat skenario.
- **README:** perintah run, aturan bisnis, batas timer, cara membaca CSV, konfigurasi,
  serta batas single-writer dan perbedaan dengan baseline insert-only.

## 4. Tests added or updated

Public seam yang diuji: Writers.write, BenchmarkService.migrate/prepareTrial/validate,
dengan PostgreSQL nyata mengikuti pola integration test yang sudah ada. Tidak menggunakan
mock database sebagai bukti performa.

45 kasus integration test mencakup:

- Insert lengkap dan validator mendeteksi nilai yang salah: empat metode.
- Update changed values, null, insert key baru, dan item tidak diberikan tetap ada: empat metode.
- Identical input tidak menghasilkan row version baru; trigger UPDATE OF memastikan
  kolom unchanged tidak muncul dalam SET pada ketiga tabel: empat metode.
- Offset timestamp dan scale decimal ekuivalen tidak menghasilkan update: empat metode.
- Baseline unchanged 1.000 user: satu kasus.
- Empat skenario dengan semua metode (termasuk mixed dengan jumlah user ganjil): 16 kasus.
- Seluruh kolom non-key mengikuti sumber yang berubah: empat metode.
- Item dihapus dari JSON tetapi tetap ada dan tidak disentuh di target: empat metode.
- Foreign key tidak valid membatalkan insert dan update dalam satu batch: empat metode.

Tujuh unit test Transformer tetap dijalankan.

Red → green yang dilakukan:

1. Test upsert existing key ditambahkan dahulu. Empat metode gagal dengan duplicate
   primary key pada kode insert-only. Implementasi ditambahkan, lalu keempat kasus lulus.
2. Test skenario unchanged ditambahkan sebelum prepareTrial tersedia. Kompilasi gagal
   karena method belum ada. Setelah persiapan skenario diimplementasikan, test lulus.

Test tambahan dijalankan sebagai regression/acceptance checks setelah slice utama,
bukan semuanya melalui siklus failing behavioral test terpisah. Dokumentasi/config
 diverifikasi lewat Docker run dan pemeriksaan CSV, bukan test unit copy statis.

## 5. Verification commands run

Dengan Java 17 lokal (`JAVA_HOME=$(/usr/libexec/java_home -v 17)`):

```bash
RUN_DB_TESTS=true mvn -B -Dtest=BenchmarkIntegrationTest#upsertUpdatesChangedValuesAndInsertsMissingKeys test
RUN_DB_TESTS=true mvn -B -Dtest=BenchmarkIntegrationTest test
RUN_DB_TESTS=true mvn -B -Dtest=BenchmarkIntegrationTest#unchangedScenarioCanBeMigratedWithoutPhysicalUpdates test
RUN_DB_TESTS=true mvn -B test
mvn -B package
```

Build/test canonical Docker:

```bash
docker compose build benchmark tests
docker compose run --rm tests
```

Smoke benchmark end-to-end:

```bash
BENCH_USERS=1000 SCENARIOS=insert,update,mixed,unchanged WARMUPS=1 REPETITIONS=2 \
  docker compose run --rm benchmark
```

Pemeriksaan data:

```bash
docker compose exec -T postgres psql -U lab -d jsonb_migration_lab -v ON_ERROR_STOP=1 < 02_report.sql
```

Script Python independen juga membaca tiga CSV dan memeriksa jumlah grup, pengecualian
warmup, perhitungan median/throughput, jumlah baris, serta agregasi waktu fase batch.
Diff source diperiksa terhadap salinan awal sesi karena folder ini tidak memiliki Git.

## 6. Lint, test, type-check, and build results

- Docker build benchmark/tests: **PASS**. Build menjalankan unit test dan membuat JAR.
- Integration + unit tests Docker: **52 tests, 0 failures, 0 errors, 0 skipped**.
- Suite lokal sebelum penambahan terakhir: **48 tests, seluruhnya lulus**.
- Maven package lokal terakhir: **PASS**, JAR runnable diperbarui. Integration test
  sengaja opt-in dan skipped pada package standar; suite lengkap telah dijalankan di Docker.
- Kompilasi/type-check Java 17: **PASS** melalui Maven dan Docker build.
- Lint terpisah: **tidak tersedia dalam konfigurasi project**, tidak diklaim lulus.
- Warning deprecated API Transformer sudah ada pada baseline; tidak diubah di scope ini.
- Smoke benchmark: **48 trial PASS** (16 warmup + 32 terukur), 96 batch, 16 summary groups.
- Pemeriksaan CSV independen: **PASS**; tiap summary berasal dari tepat dua trial terukur
  dengan scenario/method/user count yang sama. Median dan throughput sesuai data runs,
  dengan toleransi pembulatan tiga desimal.
- SQL report setelah smoke: **1.000 source users, 6.000 groups, 10.000 coupons, 3.000 benefits**;
  semua count cocok. Tabel tetap berisi hasil trial terakhir (unchanged).

Run smoke hanya untuk verification, **bukan perbandingan performa final**. Pengulangan
lima kali dan dataset 5.000/10.000 pada kode upsert baru belum dijalankan.

## 7. Deviations from the plan

Tidak ada perubahan aturan bisnis atau dependency di luar rencana. Tambahan teknis yang
 diperlukan untuk verifikasi: pemeriksaan xmin untuk no-op dan count check khusus fixture.
Test rollback lama menggunakan duplicate key; itu diganti foreign key invalid karena
 duplicate key sekarang adalah kasus upsert yang valid.

## 8. Remaining risks or work

- Read-before-write khusus satu writer. Bukan atomic ON CONFLICT; belum aman untuk
  concurrent writers atau perubahan sumber di tengah migrasi. Kasus ini di luar scope lab.
- Belum ada delete item, resume, zero downtime, atau data produksi.
- Timestamp/decimal ekuivalen diuji; distribusi dummy masih seragam dan perlu disesuaikan
  workload nyata sebelum keputusan produksi.
- CSV baru berlabel scenario; insert baru juga menjalankan lookup upsert. Jangan langsung
  dibandingkan dengan durasi insert-only lama tanpa menjelaskan perbedaan kerja.
- group_rows/coupon_rows/benefit_rows adalah item yang diproses, bukan jumlah SQL affected rows.
- Default benchmark penuh sekarang 288 migrasi; mulai dari dataset kecil.
- Perlu code review berikutnya; hasil implementasi tidak merupakan izin commit/PR.

## 9. Final git status and artifacts

`git status --short` tidak dapat digunakan: folder **bukan Git repository**.
Tidak ada branch/HEAD, staging, commit, push, maupun pull request. Tidak ada upaya membuat Git.
Status perubahan awal tidak bisa dibandingkan dengan HEAD; salinan awal source/config/docs
 disimpan di direktori temporary sebelum editing dan dijadikan pembanding sesi.

User-owned CSV insert awal dipertahankan di:
`archives/insert-before-upsert-20261004-224049/`.

Generated artifacts dari verification:

- `results/runs.csv`, `results/summary.csv`, `results/batches.csv`, `results/environment.txt`:
  smoke upsert 1.000 user, satu warmup, dua pengukuran.
- `target/`: hasil Maven package dan test lokal.
- Image Docker benchmark/tests telah dibuild; container sementara selesai dan dihapus.
- PostgreSQL lab tetap berjalan dengan data hasil smoke terakhir.

Implementation report complete. Waiting for code review.
