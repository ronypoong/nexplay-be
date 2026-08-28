-- 관리 API 로 무엇을 했는지 남긴다.
--
-- 토큰은 40자 238비트라 추측으로는 뚫리지 않는다. 진짜 위험은 새는 것이다.
-- 그런데 새더라도 누가 무엇을 했는지 알 방법이 없었다. 성공한 요청은 아무 기록도
-- 남기지 않고, 로그는 배포하면 사라진다.
--
-- 접속 주소는 그대로 남기지 않는다. 운영자 본인의 주소이고, 어느 대역에서
-- 들어왔는지만 알면 이상한 접근을 알아챌 수 있다.
CREATE TABLE admin_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(300) NOT NULL,
    status INT NOT NULL,
    ip_prefix VARCHAR(40) NULL,
    at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_admin_audit_at (at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
