-- Fecha o alerta "RLS Disabled in Public" do Supabase Security Advisor.
-- Sem policies, RLS nega tudo por padrao via Data API (PostgREST);
-- o backend continua acessando normalmente porque conecta via connection string,
-- que ignora RLS.
--
-- flyway_schema_history fica de fora de proposito: o Flyway mantem um lock
-- (pg_advisory_lock) na propria conexao/transacao da migration, e uma
-- ALTER TABLE sobre essa mesma tabela dentro dessa transacao causa deadlock
-- (statement_timeout ao tentar obter ACCESS EXCLUSIVE). RLS nela e habilitado
-- separadamente, fora do ciclo de vida do Flyway.
alter table audit_logs enable row level security;
alter table activation_tokens enable row level security;
alter table device_tokens enable row level security;
alter table password_reset_tokens enable row level security;
alter table refresh_tokens enable row level security;
alter table refuels enable row level security;
alter table vehicle_shares enable row level security;
alter table vehicle_events enable row level security;
alter table vehicles enable row level security;
alter table users enable row level security;
