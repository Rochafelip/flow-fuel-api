-- Fecha o alerta "RLS Disabled in Public" do Supabase Security Advisor.
-- Sem policies, RLS nega tudo por padrao via Data API (PostgREST);
-- o backend continua acessando normalmente porque conecta via connection string,
-- que ignora RLS.
alter table audit_logs enable row level security;
alter table flyway_schema_history enable row level security;
alter table activation_tokens enable row level security;
alter table device_tokens enable row level security;
alter table password_reset_tokens enable row level security;
alter table refresh_tokens enable row level security;
alter table refuels enable row level security;
alter table vehicle_shares enable row level security;
alter table vehicle_events enable row level security;
alter table vehicles enable row level security;
alter table users enable row level security;
