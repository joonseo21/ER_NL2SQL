"""Fixed smoke-test queries, independent of command-line presentation."""

MANUAL_QUERIES = {
    "game-count": "SELECT count(*) AS game_count FROM games",
    "character-picks": """
        SELECT character_num, count(*) AS picks
        FROM participants GROUP BY character_num ORDER BY picks DESC LIMIT 20
    """,
    "tier-average-rank": """
        SELECT tier, avg(game_rank) AS average_rank, count(*) AS samples,
               100.0 * count(*) FILTER (WHERE game_rank = 1) / NULLIF(count(*), 0) AS win_rate,
               100.0 * count(*) FILTER (WHERE game_rank <= 3) / NULLIF(count(*), 0) AS top3_rate
        FROM participants GROUP BY tier ORDER BY tier
    """,
}
