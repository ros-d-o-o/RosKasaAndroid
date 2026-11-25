-- ============================================================================
-- 04_cleanup_all_test_data.sql
-- Ciscenje VSEH danasnjih testnih zapisov na Miza 58, 59, 60 (KASIRAL = 9999)
-- POZOR: Privzeto se izvede ROLLBACK (dry-run). Za dejansko brisanje
-- zamenjaj ROLLBACK s COMMIT na koncu skripte.
-- Kodna tabela: EE8MSWIN1250 (brez UTF-8 BOM)
-- ============================================================================
SET PAGESIZE 200 LINESIZE 250 FEEDBACK ON TRIMSPOOL ON VERIFY OFF

PROMPT === PREGLED ZAPISOV ZA CISCENJE (Miza 58, 59, 60 | KASIRAL = 9999 | DANES) ===
SELECT RACUN_ID, STATUS, MARKER, ZNESEK, FISKALIZACIJA, STORNO_RACUN_ID
FROM RACGLAVA
WHERE MARKER IN ('Miza 58', 'Miza 59', 'Miza 60')
  AND KASIRAL = 9999
  AND DATUM >= TRUNC(SYSDATE)
ORDER BY RACUN_ID;

DELETE FROM RACIZPISAN
WHERE RACUN_ID IN (
    SELECT RACUN_ID FROM RACGLAVA
    WHERE MARKER IN ('Miza 58', 'Miza 59', 'Miza 60')
      AND KASIRAL = 9999
      AND DATUM >= TRUNC(SYSDATE)
);

DELETE FROM RACPLACI
WHERE RACUN_ID IN (
    SELECT RACUN_ID FROM RACGLAVA
    WHERE MARKER IN ('Miza 58', 'Miza 59', 'Miza 60')
      AND KASIRAL = 9999
      AND DATUM >= TRUNC(SYSDATE)
);

DELETE FROM RACPOZIC
WHERE RACUN_ID IN (
    SELECT RACUN_ID FROM RACGLAVA
    WHERE MARKER IN ('Miza 58', 'Miza 59', 'Miza 60')
      AND KASIRAL = 9999
      AND DATUM >= TRUNC(SYSDATE)
);

DELETE FROM RACGLAVA
WHERE MARKER IN ('Miza 58', 'Miza 59', 'Miza 60')
  AND KASIRAL = 9999
  AND DATUM >= TRUNC(SYSDATE);

-- VAROVALKA: privzeto ROLLBACK (dry-run). Za dejansko brisanje uporabi COMMIT.
ROLLBACK;

PROMPT [04] DRY-RUN KONCAN (izveden ROLLBACK). Za dejansko brisanje spremeni ROLLBACK v COMMIT.
EXIT;