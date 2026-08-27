INSERT IGNORE INTO game_genre (game_id, genre)
SELECT game_id,
       CASE genre
           WHEN '경주' THEN '레이싱'
           WHEN '오픈월드' THEN '오픈 월드'
           WHEN 'deck-building' THEN '덱빌딩'
           WHEN 'mecha' THEN '메카'
           WHEN 'baseball' THEN '야구'
           WHEN 'basketball' THEN '농구'
           WHEN 'golf' THEN '골프'
           WHEN 'cycling' THEN '사이클'
           WHEN 'card' THEN '카드'
           WHEN 'comedy' THEN '코미디'
           WHEN 'side-scrolling beat ''em up' THEN '횡스크롤 액션'
           WHEN 'arena fighter' THEN '아레나 격투'
           WHEN 'association football management game' THEN '축구 경영'
           WHEN 'board' THEN '보드게임'
           WHEN 'combat flight simulator game' THEN '비행 시뮬레이션'
           WHEN 'dark fantasy' THEN '다크 판타지'
           WHEN 'high fantasy' THEN '하이 판타지'
           WHEN 'hunting' THEN '사냥'
           WHEN 'minigame collection' THEN '미니게임 모음'
           WHEN 'parkour' THEN '파쿠르'
           WHEN 'post-apocalyptic' THEN '포스트 아포칼립스'
           WHEN 'raising sim' THEN '육성 시뮬레이션'
           WHEN 'real-time tactics' THEN '실시간 전술'
           WHEN 'turn-based tactics' THEN '턴제 전술'
           WHEN 'skateboarding' THEN '스케이트보드'
           WHEN 'soulsvania' THEN '소울라이크 메트로배니아'
           WHEN 'typing game' THEN '타이핑'
           WHEN 'Western' THEN '서부극'
           WHEN 'World War I' THEN '제1차 세계대전'
           WHEN 'Vietnam War' THEN '베트남 전쟁'
           WHEN '대규모 다중 사용자 온라인 롤 플레잉 게임(MMORPG)' THEN 'MMORPG'
           WHEN 'LGBT 등장인물이 있는' THEN 'LGBT'
           ELSE genre
       END
FROM game_genre
WHERE genre IN (
    '경주', '오픈월드', 'deck-building', 'mecha', 'baseball', 'basketball', 'golf', 'cycling',
    'card', 'comedy', 'side-scrolling beat ''em up', 'arena fighter',
    'association football management game', 'board', 'combat flight simulator game', 'dark fantasy',
    'high fantasy', 'hunting', 'minigame collection', 'parkour', 'post-apocalyptic', 'raising sim',
    'real-time tactics', 'turn-based tactics', 'skateboarding', 'soulsvania', 'typing game', 'Western',
    'World War I', 'Vietnam War', '대규모 다중 사용자 온라인 롤 플레잉 게임(MMORPG)', 'LGBT 등장인물이 있는'
);

DELETE FROM game_genre
WHERE genre IN (
    '경주', '오픈월드', 'deck-building', 'mecha', 'baseball', 'basketball', 'golf', 'cycling',
    'card', 'comedy', 'side-scrolling beat ''em up', 'arena fighter',
    'association football management game', 'board', 'combat flight simulator game', 'dark fantasy',
    'high fantasy', 'hunting', 'minigame collection', 'parkour', 'post-apocalyptic', 'raising sim',
    'real-time tactics', 'turn-based tactics', 'skateboarding', 'soulsvania', 'typing game', 'Western',
    'World War I', 'Vietnam War', '대규모 다중 사용자 온라인 롤 플레잉 게임(MMORPG)', 'LGBT 등장인물이 있는'
);
