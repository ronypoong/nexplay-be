INSERT INTO company (id, slug, name) VALUES
  (1, 'northstar-works', 'Northstar Works'), (2, 'arc-and-vale', 'Arc & Vale'),
  (3, 'quiet-giant', 'Quiet Giant'), (4, 'self-published', 'Self-published'),
  (5, 'mossbell', 'Mossbell'), (6, 'bramble-games', 'Bramble Games'),
  (7, 'null-field', 'Null Field'), (8, 'obscura', 'Obscura'),
  (9, 'gogo-studio', 'GOGO Studio'), (10, 'playframe', 'Playframe'),
  (11, 'tempo-lab', 'Tempo Lab'), (12, 'neon-lace', 'Neon Lace');

INSERT INTO game (id, slug, title, tagline, description, developer_id, publisher_id, release_date, release_label, status, discovery_score, follower_count, accent, accent_secondary, symbol, featured) VALUES
  (1, 'echoes-of-elysium', 'Echoes of Elysium', '하늘 위에서 시작되는 마지막 탐험', '부유 군도를 횡단하며 사라진 문명의 기억을 복원하는 오픈월드 액션 RPG. 비행선 커스터마이징과 4인 협동 탐험을 지원합니다.', 1, 2, '2026-10-22', '2026. 10. 22', 'UPCOMING', 98, 42800, '#6e51ff', '#fc7b45', '✦', b'1'),
  (2, 'project-aurora', 'Project Aurora', '혹한의 행성, 혼자가 아닌 생존', '끊임없이 변화하는 설원 기지에서 생존하고 탐사하는 내러티브 SF 어드벤처입니다.', 3, 4, '2027-02-12', '2027. 02. 12', 'UPCOMING', 94, 28100, '#1f78ff', '#6be7dd', '◈', b'0'),
  (3, 'little-witch-market', 'Little Witch Market', '낮에는 장사, 밤에는 마법', '작은 마을에서 마법 상점을 운영하고 주민들의 비밀을 발견하는 아늑한 생활 시뮬레이션입니다.', 5, 6, '2026-09-18', '2026. 09. 18', 'UPCOMING', 91, 19400, '#e84b8a', '#ffbb5c', '✿', b'0'),
  (4, 'dead-signal', 'Dead Signal', '신호를 들었다면 이미 늦었다', '버려진 심우주 중계소에서 벌어지는 1인칭 심리 공포 게임입니다.', 7, 8, '2026-11-06', '2026. 11. 06', 'UPCOMING', 89, 16700, '#3d5364', '#e02f48', '⌁', b'0'),
  (5, 'kaiju-club', 'Kaiju Club', '괴수도 퇴근 후엔 친구가 필요해', '괴수들의 비밀 아지트를 꾸미는 유쾌한 협동 파티 게임입니다.', 9, 10, '2026-08-29', 'D-3 · 08. 29', 'UPCOMING', 87, 12300, '#ff5d3c', '#ffd542', '♢', b'0'),
  (6, 'velvet-circuit', 'Velvet Circuit', '속도와 리듬이 만나는 밤', '네온 메트로폴리스를 질주하는 스타일리시 아케이드 레이싱 게임입니다.', 11, 12, NULL, '출시일 미정', 'TBA', 84, 8900, '#a22bff', '#17d9ff', '⌁', b'0');

INSERT INTO game_genre (game_id, genre) VALUES
  (1, 'Action RPG'), (1, 'Open World'), (2, 'Adventure'), (2, 'Survival'),
  (3, 'Simulation'), (3, 'Cozy'), (4, 'Horror'), (4, 'Sci-Fi'),
  (5, 'Party'), (5, 'Co-op'), (6, 'Racing'), (6, 'Rhythm');

INSERT INTO game_platform (game_id, platform) VALUES
  (1, 'PC'), (1, 'PS5'), (1, 'Xbox'), (2, 'PC'), (2, 'PS5'),
  (3, 'PC'), (3, 'Switch 2'), (4, 'PC'), (4, 'PS5'), (4, 'Xbox'),
  (5, 'PC'), (5, 'Switch 2'), (5, 'PS5'), (6, 'PC'), (6, 'PS5');

INSERT INTO source (id, slug, name, base_url, official) VALUES
  (1, 'northstar-works', 'Northstar Works', 'https://example.com/northstar', b'1'),
  (2, 'bramble-games', 'Bramble Games', 'https://example.com/bramble', b'1'),
  (3, 'quiet-giant', 'Quiet Giant', 'https://example.com/quiet-giant', b'1'),
  (4, 'obscura', 'Obscura', 'https://example.com/obscura', b'1'),
  (5, 'summer-game-stage', 'Summer Game Stage', 'https://example.com/sgs', b'0'),
  (6, 'steam', 'Steam', 'https://store.steampowered.com', b'0'),
  (7, 'youtube', 'YouTube Gaming', 'https://youtube.com', b'0'),
  (8, 'gamewire', 'GameWire', 'https://example.com/gamewire', b'0');

INSERT INTO game_event (id, game_id, type, title, summary, event_date, published_at) VALUES
  (1, 1, 'GAMEPLAY', '12분 공식 게임플레이 최초 공개', '비행선 전투, 협동 던전, 날씨 시스템을 한 번에 확인할 수 있습니다.', '2026-08-26', '2026-08-26 05:00:00'),
  (2, 3, 'RELEASE_DATE', '9월 18일 출시 확정', 'PC와 Switch 2 동시 출시. 예약 구매 특전도 함께 공개됐습니다.', '2026-08-26', '2026-08-26 03:00:00'),
  (3, 2, 'TRAILER', '신규 스토리 트레일러 공개', '얼어붙은 행성의 구조 요청과 탐사대의 과거를 다룹니다.', '2026-08-26', '2026-08-26 00:30:00'),
  (4, 4, 'ANNOUNCEMENT', 'Dead Signal 깜짝 발표', 'SOMA와 Alien: Isolation에서 영감을 받은 심리 공포 신작입니다.', '2026-08-25', '2026-08-25 11:00:00'),
  (5, 1, 'RELEASE_DATE', '글로벌 출시일 발표', '2026년 10월 22일, PC와 콘솔에 동시 출시됩니다.', '2026-08-19', '2026-08-19 09:00:00'),
  (6, 1, 'ANNOUNCEMENT', 'Echoes of Elysium 공식 발표', 'Northstar Works의 신규 IP가 Summer Game Stage에서 처음 공개됐습니다.', '2026-06-12', '2026-06-12 10:00:00');

INSERT INTO game_event_source (game_event_id, source_id, source_url) VALUES
  (1, 1, 'https://example.com/northstar/elysium-gameplay'), (1, 7, 'https://youtube.com/watch?v=elysium-gameplay'), (1, 8, 'https://example.com/gamewire/elysium'),
  (2, 2, 'https://example.com/bramble/release-date'), (2, 6, 'https://store.steampowered.com/app/little-witch-market'), (2, 7, 'https://youtube.com/watch?v=lwm-date'),
  (3, 3, 'https://example.com/quiet-giant/aurora-trailer'), (3, 7, 'https://youtube.com/watch?v=aurora'),
  (4, 4, 'https://example.com/obscura/dead-signal'), (4, 5, 'https://example.com/sgs/dead-signal'), (4, 8, 'https://example.com/gamewire/dead-signal'),
  (5, 1, 'https://example.com/northstar/elysium-date'), (5, 6, 'https://store.steampowered.com/app/echoes-of-elysium'),
  (6, 5, 'https://example.com/sgs/elysium'), (6, 7, 'https://youtube.com/watch?v=elysium-announce'), (6, 8, 'https://example.com/gamewire/elysium-announce');

INSERT INTO game_release (game_id, platform, release_date, status, region) VALUES
  (5, 'PC', '2026-08-29', 'CONFIRMED', 'GLOBAL'), (5, 'PS5', '2026-08-29', 'CONFIRMED', 'GLOBAL'), (5, 'Switch 2', '2026-08-29', 'CONFIRMED', 'GLOBAL'),
  (3, 'PC', '2026-09-18', 'CONFIRMED', 'GLOBAL'), (3, 'Switch 2', '2026-09-18', 'CONFIRMED', 'GLOBAL'),
  (1, 'PC', '2026-10-22', 'CONFIRMED', 'GLOBAL'), (1, 'PS5', '2026-10-22', 'CONFIRMED', 'GLOBAL'), (1, 'Xbox', '2026-10-22', 'CONFIRMED', 'GLOBAL'),
  (4, 'PC', '2026-11-06', 'CONFIRMED', 'GLOBAL'), (4, 'PS5', '2026-11-06', 'CONFIRMED', 'GLOBAL'), (4, 'Xbox', '2026-11-06', 'CONFIRMED', 'GLOBAL'),
  (2, 'PC', '2027-02-12', 'EXPECTED', 'GLOBAL'), (2, 'PS5', '2027-02-12', 'EXPECTED', 'GLOBAL');
