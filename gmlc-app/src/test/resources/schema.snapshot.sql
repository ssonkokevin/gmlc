# generated from Flyway migrations; do not edit by hand
# regenerate with -Dgmlc.schema.snapshot=write
column api_client.client_id uuid nullable=NO default= identity=NO generated=NO
column api_client.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column api_client.enabled boolean nullable=NO default=true identity=NO generated=NO
column api_client.ip_allowlist text[] nullable=YES default= identity=NO generated=NO
column api_client.key_hash character(64) nullable=NO default= identity=NO generated=NO
column api_client.max_accuracy_m integer nullable=NO default=5000 identity=NO generated=NO
column api_client.name character varying(128) nullable=NO default= identity=NO generated=NO
column api_client.purposes text[] nullable=NO default='{}'::text[] identity=NO generated=NO
column api_client.rate_per_day integer nullable=NO default=1000 identity=NO generated=NO
column api_client.rate_per_second integer nullable=NO default=5 identity=NO generated=NO
column api_client.tenant_id bigint nullable=NO default= identity=NO generated=NO
column audit_event.action character varying(48) nullable=NO default= identity=NO generated=NO
column audit_event.actor_client_id uuid nullable=YES default= identity=NO generated=NO
column audit_event.actor_user_id uuid nullable=YES default= identity=NO generated=NO
column audit_event.audit_id bigint nullable=NO default=nextval(audit_event_audit_id_seq) identity=NO generated=NO
column audit_event.detail jsonb nullable=YES default= identity=NO generated=NO
column audit_event.occurred_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column audit_event.outcome character varying(16) nullable=NO default= identity=NO generated=NO
column audit_event.request_id uuid nullable=YES default= identity=NO generated=NO
column audit_event.result_ref uuid nullable=YES default= identity=NO generated=NO
column audit_event.subject_ref character(64) nullable=YES default= identity=NO generated=NO
column audit_event.tenant_id bigint nullable=NO default= identity=NO generated=NO
column capability_profile.profile jsonb nullable=NO default= identity=NO generated=NO
column capability_profile.tenant_id bigint nullable=NO default= identity=NO generated=NO
column capability_profile.updated_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column capability_profile.version character varying(16) nullable=NO default= identity=NO generated=NO
column cdr.accuracy_achieved_m integer nullable=YES default= identity=NO generated=NO
column cdr.accuracy_fulfilled boolean nullable=YES default= identity=NO generated=NO
column cdr.accuracy_requested_m integer nullable=YES default= identity=NO generated=NO
column cdr.age_of_location_s integer nullable=YES default= identity=NO generated=NO
column cdr.cdr_id bigint nullable=NO default=nextval(cdr_cdr_id_seq) identity=NO generated=NO
column cdr.client_id uuid nullable=NO default= identity=NO generated=NO
column cdr.confidence smallint nullable=YES default= identity=NO generated=NO
column cdr.consent_id uuid nullable=YES default= identity=NO generated=NO
column cdr.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column cdr.latitude numeric(9,7) nullable=YES default= identity=NO generated=NO
column cdr.longitude numeric(10,7) nullable=YES default= identity=NO generated=NO
column cdr.methods_attempted text[] nullable=YES default= identity=NO generated=NO
column cdr.operation character varying(24) nullable=YES default= identity=NO generated=NO
column cdr.paging_performed boolean nullable=NO default=false identity=NO generated=NO
column cdr.polygon jsonb nullable=YES default= identity=NO generated=NO
column cdr.positioning_method character varying(16) nullable=YES default= identity=NO generated=NO
column cdr.positioning_node character varying(64) nullable=YES default= identity=NO generated=NO
column cdr.protocol character varying(16) nullable=YES default= identity=NO generated=NO
column cdr.rat_type character varying(8) nullable=YES default= identity=NO generated=NO
column cdr.record_status character varying(16) nullable=NO default= identity=NO generated=NO
column cdr.request_id uuid nullable=NO default= identity=NO generated=NO
column cdr.response_time_ms integer nullable=YES default= identity=NO generated=NO
column cdr.result_code character varying(64) nullable=NO default= identity=NO generated=NO
column cdr.serving_node character varying(64) nullable=YES default= identity=NO generated=NO
column cdr.shape character varying(8) nullable=YES default= identity=NO generated=NO
column cdr.subscriber_ref character(64) nullable=NO default= identity=NO generated=NO
column cdr.tenant_id bigint nullable=NO default= identity=NO generated=NO
column cdr.uncertainty_m integer nullable=YES default= identity=NO generated=NO
column cell.antenna_height_m numeric(6,1) nullable=YES default= identity=NO generated=NO
column cell.area_code character varying(16) nullable=YES default= identity=NO generated=NO
column cell.arfcn integer nullable=YES default= identity=NO generated=NO
column cell.azimuth_deg smallint nullable=YES default= identity=NO generated=NO
column cell.beamwidth_deg smallint nullable=YES default= identity=NO generated=NO
column cell.cell_code character varying(16) nullable=NO default= identity=NO generated=NO
column cell.cell_identity character varying(32) nullable=NO default= identity=NO generated=NO
column cell.cell_type character varying(8) nullable=NO default='UNKNOWN'::character varying identity=NO generated=NO
column cell.coverage_polygon jsonb nullable=YES default= identity=NO generated=NO
column cell.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column cell.data_quality smallint nullable=YES default= identity=NO generated=NO
column cell.data_source character varying(64) nullable=YES default= identity=NO generated=NO
column cell.last_verified date nullable=YES default= identity=NO generated=NO
column cell.latitude numeric(9,7) nullable=YES default= identity=NO generated=NO
column cell.longitude numeric(10,7) nullable=YES default= identity=NO generated=NO
column cell.pci smallint nullable=YES default= identity=NO generated=NO
column cell.plmn_mcc character(3) nullable=NO default= identity=NO generated=NO
column cell.plmn_mnc character varying(3) nullable=NO default= identity=NO generated=NO
column cell.prs_config jsonb nullable=YES default= identity=NO generated=NO
column cell.radius_m integer nullable=YES default= identity=NO generated=NO
column cell.rat character varying(8) nullable=NO default= identity=NO generated=NO
column cell.site_name character varying(128) nullable=YES default= identity=NO generated=NO
column cell.tenant_id bigint nullable=NO default= identity=NO generated=NO
column cell.timing_advance jsonb nullable=YES default= identity=NO generated=NO
column cell.updated_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column cell.valid_from timestamp with time zone nullable=YES default= identity=NO generated=NO
column cell.valid_to timestamp with time zone nullable=YES default= identity=NO generated=NO
column client_usage.client_id uuid nullable=NO default= identity=NO generated=NO
column client_usage.counter integer nullable=NO default= identity=NO generated=NO
column client_usage.tenant_id bigint nullable=NO default= identity=NO generated=NO
column client_usage.window_kind character varying(8) nullable=NO default= identity=NO generated=NO
column client_usage.window_start timestamp with time zone nullable=NO default= identity=NO generated=NO
column config_change.applied_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column config_change.applied_version integer nullable=YES default= identity=NO generated=NO
column config_change.base_version integer nullable=NO default= identity=NO generated=NO
column config_change.change_id uuid nullable=NO default= identity=NO generated=NO
column config_change.decided_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column config_change.decided_by uuid nullable=YES default= identity=NO generated=NO
column config_change.decision_note character varying(500) nullable=YES default= identity=NO generated=NO
column config_change.item_key character varying(64) nullable=NO default= identity=NO generated=NO
column config_change.previous_value jsonb nullable=YES default= identity=NO generated=NO
column config_change.reason character varying(500) nullable=NO default= identity=NO generated=NO
column config_change.requested_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column config_change.requested_by uuid nullable=NO default= identity=NO generated=NO
column config_change.requested_value jsonb nullable=NO default= identity=NO generated=NO
column config_change.requires_approval boolean nullable=NO default= identity=NO generated=NO
column config_change.status character varying(16) nullable=NO default= identity=NO generated=NO
column config_change.tenant_id bigint nullable=NO default= identity=NO generated=NO
column config_item.item_key character varying(64) nullable=NO default= identity=NO generated=NO
column config_item.tenant_id bigint nullable=NO default= identity=NO generated=NO
column config_item.updated_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column config_item.updated_by uuid nullable=NO default= identity=NO generated=NO
column config_item.value jsonb nullable=NO default= identity=NO generated=NO
column config_item.version integer nullable=NO default= identity=NO generated=NO
column consent.channel character varying(16) nullable=YES default= identity=NO generated=NO
column consent.client_id uuid nullable=YES default= identity=NO generated=NO
column consent.consent_id uuid nullable=NO default= identity=NO generated=NO
column consent.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column consent.evidence jsonb nullable=YES default= identity=NO generated=NO
column consent.expires_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column consent.granted_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column consent.purpose character varying(64) nullable=NO default= identity=NO generated=NO
column consent.revoked_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column consent.status character varying(16) nullable=NO default= identity=NO generated=NO
column consent.subject_ref character(64) nullable=NO default= identity=NO generated=NO
column consent.tenant_id bigint nullable=NO default= identity=NO generated=NO
column event_subscription.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column event_subscription.created_by uuid nullable=YES default= identity=NO generated=NO
column event_subscription.enabled boolean nullable=NO default=true identity=NO generated=NO
column event_subscription.event_types text[] nullable=NO default= identity=NO generated=NO
column event_subscription.geofence_id uuid nullable=YES default= identity=NO generated=NO
column event_subscription.name character varying(128) nullable=NO default= identity=NO generated=NO
column event_subscription.secret character varying(128) nullable=NO default= identity=NO generated=NO
column event_subscription.subscription_id uuid nullable=NO default= identity=NO generated=NO
column event_subscription.tenant_id bigint nullable=NO default= identity=NO generated=NO
column event_subscription.updated_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column event_subscription.url character varying(1024) nullable=NO default= identity=NO generated=NO
column geofence.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column geofence.created_by uuid nullable=YES default= identity=NO generated=NO
column geofence.description character varying(512) nullable=YES default= identity=NO generated=NO
column geofence.enabled boolean nullable=NO default=true identity=NO generated=NO
column geofence.geofence_id uuid nullable=NO default= identity=NO generated=NO
column geofence.geometry jsonb nullable=NO default= identity=NO generated=NO
column geofence.group_name character varying(64) nullable=YES default= identity=NO generated=NO
column geofence.name character varying(128) nullable=NO default= identity=NO generated=NO
column geofence.report_inside boolean nullable=NO default=false identity=NO generated=NO
column geofence.shape character varying(16) nullable=NO default= identity=NO generated=NO
column geofence.tenant_id bigint nullable=NO default= identity=NO generated=NO
column geofence.updated_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column geofence_arm.armed_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column geofence_arm.armed_by uuid nullable=YES default= identity=NO generated=NO
column geofence_arm.geofence_id uuid nullable=NO default= identity=NO generated=NO
column geofence_arm.last_evaluated_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column geofence_arm.last_result_id uuid nullable=YES default= identity=NO generated=NO
column geofence_arm.state character varying(8) nullable=YES default= identity=NO generated=NO
column geofence_arm.subject_label character varying(24) nullable=NO default= identity=NO generated=NO
column geofence_arm.subject_ref character(64) nullable=NO default= identity=NO generated=NO
column geofence_arm.tenant_id bigint nullable=NO default= identity=NO generated=NO
column geofence_event.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column geofence_event.event_id bigint nullable=NO default=nextval(geofence_event_event_id_seq) identity=NO generated=NO
column geofence_event.event_type character varying(8) nullable=NO default= identity=NO generated=NO
column geofence_event.geofence_id uuid nullable=NO default= identity=NO generated=NO
column geofence_event.latitude numeric(9,6) nullable=NO default= identity=NO generated=NO
column geofence_event.longitude numeric(9,6) nullable=NO default= identity=NO generated=NO
column geofence_event.method character varying(32) nullable=NO default= identity=NO generated=NO
column geofence_event.occurred_at timestamp with time zone nullable=NO default= identity=NO generated=NO
column geofence_event.request_id uuid nullable=NO default= identity=NO generated=NO
column geofence_event.result_id uuid nullable=NO default= identity=NO generated=NO
column geofence_event.subject_label character varying(24) nullable=NO default= identity=NO generated=NO
column geofence_event.subject_ref character(64) nullable=NO default= identity=NO generated=NO
column geofence_event.tenant_id bigint nullable=NO default= identity=NO generated=NO
column geofence_event.uncertainty_m integer nullable=NO default= identity=NO generated=NO
column location_request.client_id uuid nullable=NO default= identity=NO generated=NO
column location_request.completed_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column location_request.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column location_request.deadline_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column location_request.error_code character varying(64) nullable=YES default= identity=NO generated=NO
column location_request.error_detail text nullable=YES default= identity=NO generated=NO
column location_request.idempotency_key character varying(80) nullable=YES default= identity=NO generated=NO
column location_request.paging_performed boolean nullable=NO default=false identity=NO generated=NO
column location_request.path character varying(16) nullable=YES default= identity=NO generated=NO
column location_request.positioning_node character varying(64) nullable=YES default= identity=NO generated=NO
column location_request.protocol character varying(16) nullable=YES default= identity=NO generated=NO
column location_request.purpose character varying(64) nullable=NO default= identity=NO generated=NO
column location_request.rat character varying(8) nullable=YES default= identity=NO generated=NO
column location_request.request_id uuid nullable=NO default= identity=NO generated=NO
column location_request.requested_accuracy_m integer nullable=YES default= identity=NO generated=NO
column location_request.requested_priority character varying(8) nullable=NO default='NORMAL'::character varying identity=NO generated=NO
column location_request.result_id uuid nullable=YES default= identity=NO generated=NO
column location_request.serving_node character varying(64) nullable=YES default= identity=NO generated=NO
column location_request.state character varying(24) nullable=NO default= identity=NO generated=NO
column location_request.subscriber_imsi character varying(15) nullable=YES default= identity=NO generated=NO
column location_request.subscriber_msisdn character varying(20) nullable=YES default= identity=NO generated=NO
column location_request.tenant_id bigint nullable=NO default= identity=NO generated=NO
column location_request.updated_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column location_result.accuracy_fulfilled boolean nullable=NO default= identity=NO generated=NO
column location_result.accuracy_requested_m integer nullable=YES default= identity=NO generated=NO
column location_result.age_seconds integer nullable=NO default= identity=NO generated=NO
column location_result.altitude_m numeric(7,1) nullable=YES default= identity=NO generated=NO
column location_result.confidence smallint nullable=NO default= identity=NO generated=NO
column location_result.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column location_result.engine character varying(16) nullable=YES default= identity=NO generated=NO
column location_result.geometry_data_quality smallint nullable=YES default= identity=NO generated=NO
column location_result.latitude numeric(9,7) nullable=YES default= identity=NO generated=NO
column location_result.longitude numeric(10,7) nullable=YES default= identity=NO generated=NO
column location_result.method character varying(16) nullable=NO default= identity=NO generated=NO
column location_result.methods_attempted text[] nullable=NO default= identity=NO generated=NO
column location_result.payload jsonb nullable=YES default= identity=NO generated=NO
column location_result.positioning_node character varying(64) nullable=YES default= identity=NO generated=NO
column location_result.produced_at timestamp with time zone nullable=NO default= identity=NO generated=NO
column location_result.request_id uuid nullable=NO default= identity=NO generated=NO
column location_result.result_id uuid nullable=NO default= identity=NO generated=NO
column location_result.serving_cell character varying(32) nullable=YES default= identity=NO generated=NO
column location_result.shape character varying(8) nullable=NO default= identity=NO generated=NO
column location_result.tenant_id bigint nullable=NO default= identity=NO generated=NO
column location_result.uncertainty_m integer nullable=NO default= identity=NO generated=NO
column notification_channel.channel_id uuid nullable=NO default= identity=NO generated=NO
column notification_channel.channel_type character varying(16) nullable=NO default= identity=NO generated=NO
column notification_channel.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column notification_channel.created_by uuid nullable=YES default= identity=NO generated=NO
column notification_channel.enabled boolean nullable=NO default=true identity=NO generated=NO
column notification_channel.name character varying(128) nullable=NO default= identity=NO generated=NO
column notification_channel.secret_sealed text nullable=NO default= identity=NO generated=NO
column notification_channel.tenant_id bigint nullable=NO default= identity=NO generated=NO
column notification_channel.updated_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column notification_channel.url character varying(1024) nullable=NO default= identity=NO generated=NO
column ops_alert.acknowledged_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column ops_alert.acknowledged_by uuid nullable=YES default= identity=NO generated=NO
column ops_alert.alert_id bigint nullable=NO default=nextval(ops_alert_alert_id_seq) identity=NO generated=NO
column ops_alert.closed_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column ops_alert.dedupe_key character varying(96) nullable=NO default= identity=NO generated=NO
column ops_alert.detail jsonb nullable=YES default= identity=NO generated=NO
column ops_alert.kind character varying(32) nullable=NO default= identity=NO generated=NO
column ops_alert.last_seen_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column ops_alert.opened_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column ops_alert.severity character varying(8) nullable=NO default= identity=NO generated=NO
column ops_alert.summary text nullable=NO default= identity=NO generated=NO
column ops_alert.tenant_id bigint nullable=NO default= identity=NO generated=NO
column ops_break_glass.ended_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column ops_break_glass.expires_at timestamp with time zone nullable=NO default= identity=NO generated=NO
column ops_break_glass.grant_id uuid nullable=NO default= identity=NO generated=NO
column ops_break_glass.granted_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column ops_break_glass.reason character varying(500) nullable=NO default= identity=NO generated=NO
column ops_break_glass.review_note character varying(500) nullable=YES default= identity=NO generated=NO
column ops_break_glass.review_status character varying(16) nullable=NO default='PENDING'::character varying identity=NO generated=NO
column ops_break_glass.reviewed_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column ops_break_glass.reviewed_by uuid nullable=YES default= identity=NO generated=NO
column ops_break_glass.session_id uuid nullable=YES default= identity=NO generated=NO
column ops_break_glass.tenant_id bigint nullable=NO default= identity=NO generated=NO
column ops_break_glass.user_id uuid nullable=NO default= identity=NO generated=NO
column ops_session.absolute_expires_at timestamp with time zone nullable=NO default= identity=NO generated=NO
column ops_session.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column ops_session.csrf_token character varying(64) nullable=NO default= identity=NO generated=NO
column ops_session.idle_expires_at timestamp with time zone nullable=NO default= identity=NO generated=NO
column ops_session.last_seen_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column ops_session.remote_addr character varying(64) nullable=YES default= identity=NO generated=NO
column ops_session.revoked_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column ops_session.session_id uuid nullable=NO default= identity=NO generated=NO
column ops_session.tenant_id bigint nullable=NO default= identity=NO generated=NO
column ops_session.token_hash character(64) nullable=NO default= identity=NO generated=NO
column ops_session.user_id uuid nullable=NO default= identity=NO generated=NO
column ops_user.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column ops_user.display_name character varying(128) nullable=NO default= identity=NO generated=NO
column ops_user.enabled boolean nullable=NO default=true identity=NO generated=NO
column ops_user.failed_attempts integer nullable=NO default=0 identity=NO generated=NO
column ops_user.last_login_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column ops_user.locked_until timestamp with time zone nullable=YES default= identity=NO generated=NO
column ops_user.password_changed_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column ops_user.password_hash character varying(255) nullable=NO default= identity=NO generated=NO
column ops_user.role character varying(16) nullable=NO default= identity=NO generated=NO
column ops_user.tenant_id bigint nullable=NO default= identity=NO generated=NO
column ops_user.totp_secret character varying(64) nullable=NO default= identity=NO generated=NO
column ops_user.user_id uuid nullable=NO default= identity=NO generated=NO
column ops_user.username character varying(64) nullable=NO default= identity=NO generated=NO
column tenant.active boolean nullable=NO default=true identity=NO generated=NO
column tenant.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column tenant.name character varying(128) nullable=NO default= identity=NO generated=NO
column tenant.tenant_id bigint nullable=NO default= identity=NO generated=NO
column tracked_subject.client_id uuid nullable=NO default= identity=NO generated=NO
column tracked_subject.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column tracked_subject.created_by uuid nullable=YES default= identity=NO generated=NO
column tracked_subject.enabled boolean nullable=NO default=true identity=NO generated=NO
column tracked_subject.purpose character varying(32) nullable=NO default= identity=NO generated=NO
column tracked_subject.subject_imsi character varying(15) nullable=YES default= identity=NO generated=NO
column tracked_subject.subject_label character varying(24) nullable=NO default= identity=NO generated=NO
column tracked_subject.subject_msisdn character varying(20) nullable=YES default= identity=NO generated=NO
column tracked_subject.subject_ref character(64) nullable=NO default= identity=NO generated=NO
column tracked_subject.tenant_id bigint nullable=NO default= identity=NO generated=NO
column tracked_subject.tracked_id uuid nullable=NO default= identity=NO generated=NO
column tracked_subject.updated_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column tracking_run.error_code character varying(64) nullable=YES default= identity=NO generated=NO
column tracking_run.finished_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column tracking_run.notify_detail character varying(256) nullable=YES default= identity=NO generated=NO
column tracking_run.notify_state character varying(16) nullable=NO default='NONE'::character varying identity=NO generated=NO
column tracking_run.occurred_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column tracking_run.request_id uuid nullable=YES default= identity=NO generated=NO
column tracking_run.run_id bigint nullable=NO default=nextval(tracking_run_run_id_seq) identity=NO generated=NO
column tracking_run.schedule_id uuid nullable=NO default= identity=NO generated=NO
column tracking_run.state character varying(24) nullable=NO default= identity=NO generated=NO
column tracking_run.tenant_id bigint nullable=NO default= identity=NO generated=NO
column tracking_run.trigger_kind character varying(16) nullable=NO default= identity=NO generated=NO
column tracking_schedule.accuracy_m integer nullable=YES default= identity=NO generated=NO
column tracking_schedule.channel_id uuid nullable=YES default= identity=NO generated=NO
column tracking_schedule.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column tracking_schedule.created_by uuid nullable=YES default= identity=NO generated=NO
column tracking_schedule.enabled boolean nullable=NO default=true identity=NO generated=NO
column tracking_schedule.ends_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column tracking_schedule.interval_seconds integer nullable=NO default= identity=NO generated=NO
column tracking_schedule.last_error character varying(64) nullable=YES default= identity=NO generated=NO
column tracking_schedule.last_run_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column tracking_schedule.last_state character varying(24) nullable=YES default= identity=NO generated=NO
column tracking_schedule.next_run_at timestamp with time zone nullable=NO default= identity=NO generated=NO
column tracking_schedule.notify boolean nullable=NO default=false identity=NO generated=NO
column tracking_schedule.schedule_id uuid nullable=NO default= identity=NO generated=NO
column tracking_schedule.starts_at timestamp with time zone nullable=NO default= identity=NO generated=NO
column tracking_schedule.tenant_id bigint nullable=NO default= identity=NO generated=NO
column tracking_schedule.tracked_id uuid nullable=NO default= identity=NO generated=NO
column tracking_schedule.updated_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column webhook_delivery.attempts integer nullable=NO default=0 identity=NO generated=NO
column webhook_delivery.created_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column webhook_delivery.delivered_at timestamp with time zone nullable=YES default= identity=NO generated=NO
column webhook_delivery.delivery_id bigint nullable=NO default=nextval(webhook_delivery_delivery_id_seq) identity=NO generated=NO
column webhook_delivery.event_id bigint nullable=NO default= identity=NO generated=NO
column webhook_delivery.last_error character varying(256) nullable=YES default= identity=NO generated=NO
column webhook_delivery.last_status_code integer nullable=YES default= identity=NO generated=NO
column webhook_delivery.next_attempt_at timestamp with time zone nullable=NO default=now() identity=NO generated=NO
column webhook_delivery.status character varying(16) nullable=NO default= identity=NO generated=NO
column webhook_delivery.subscription_id uuid nullable=NO default= identity=NO generated=NO
column webhook_delivery.tenant_id bigint nullable=NO default= identity=NO generated=NO
constraint api_client api_client_key_hash_key UNIQUE (key_hash)
constraint api_client api_client_max_accuracy_m_check CHECK ((max_accuracy_m > 0))
constraint api_client api_client_pkey PRIMARY KEY (client_id)
constraint api_client api_client_rate_per_day_check CHECK ((rate_per_day > 0))
constraint api_client api_client_rate_per_second_check CHECK ((rate_per_second > 0))
constraint api_client api_client_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint api_client api_client_tenant_id_name_key UNIQUE (tenant_id, name)
constraint audit_event audit_event_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['ALLOWED'::character varying, 'DENIED'::character varying, 'ERROR'::character varying])::text[])))
constraint audit_event audit_event_pkey PRIMARY KEY (tenant_id, audit_id)
constraint audit_event audit_event_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint capability_profile capability_profile_pkey PRIMARY KEY (tenant_id)
constraint capability_profile capability_profile_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint cdr cdr_pkey PRIMARY KEY (tenant_id, cdr_id)
constraint cdr cdr_record_status_check CHECK (((record_status)::text = ANY ((ARRAY['ANSWERED'::character varying, 'FAILED'::character varying, 'ABORTED'::character varying, 'TIMEOUT'::character varying])::text[])))
constraint cdr cdr_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint cell cell_azimuth_deg_check CHECK (((azimuth_deg >= 0) AND (azimuth_deg <= 359)))
constraint cell cell_beamwidth_deg_check CHECK (((beamwidth_deg >= 0) AND (beamwidth_deg <= 360)))
constraint cell cell_cell_type_check CHECK (((cell_type)::text = ANY ((ARRAY['MACRO'::character varying, 'MICRO'::character varying, 'INDOOR'::character varying, 'UNKNOWN'::character varying])::text[])))
constraint cell cell_data_quality_check CHECK (((data_quality >= 0) AND (data_quality <= 100)))
constraint cell cell_has_geometry CHECK (((coverage_polygon IS NOT NULL) OR ((latitude IS NOT NULL) AND (longitude IS NOT NULL))))
constraint cell cell_pkey PRIMARY KEY (tenant_id, rat, cell_identity)
constraint cell cell_radius_m_check CHECK ((radius_m > 0))
constraint cell cell_rat_check CHECK (((rat)::text = ANY ((ARRAY['GSM'::character varying, 'UMTS'::character varying, 'LTE'::character varying, 'NR'::character varying])::text[])))
constraint cell cell_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint client_usage client_usage_client_id_fkey FOREIGN KEY (client_id) REFERENCES api_client(client_id)
constraint client_usage client_usage_counter_check CHECK ((counter >= 0))
constraint client_usage client_usage_pkey PRIMARY KEY (tenant_id, client_id, window_kind, window_start)
constraint client_usage client_usage_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint client_usage client_usage_window_kind_check CHECK (((window_kind)::text = ANY ((ARRAY['SECOND'::character varying, 'DAY'::character varying])::text[])))
constraint config_change config_change_applied_pair CHECK (((applied_version IS NULL) = (applied_at IS NULL)))
constraint config_change config_change_base_version_check CHECK ((base_version >= 0))
constraint config_change config_change_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES ops_user(user_id)
constraint config_change config_change_decided_pair CHECK (((decided_by IS NULL) = (decided_at IS NULL)))
constraint config_change config_change_pkey PRIMARY KEY (change_id)
constraint config_change config_change_reason_present CHECK ((length(btrim((reason)::text)) >= 10))
constraint config_change config_change_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES ops_user(user_id)
constraint config_change config_change_second_approver CHECK ((((status)::text <> 'APPLIED'::text) OR (NOT requires_approval) OR (decided_by IS NULL) OR (decided_by <> requested_by)))
constraint config_change config_change_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPLIED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[])))
constraint config_change config_change_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint config_item config_item_pkey PRIMARY KEY (tenant_id, item_key)
constraint config_item config_item_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint config_item config_item_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES ops_user(user_id)
constraint config_item config_item_version_check CHECK ((version >= 1))
constraint consent consent_client_id_fkey FOREIGN KEY (client_id) REFERENCES api_client(client_id)
constraint consent consent_granted_has_time CHECK ((((status)::text <> 'GRANTED'::text) OR (granted_at IS NOT NULL)))
constraint consent consent_pkey PRIMARY KEY (consent_id)
constraint consent consent_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'GRANTED'::character varying, 'REVOKED'::character varying, 'EXPIRED'::character varying])::text[])))
constraint consent consent_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint event_subscription event_subscription_created_by_fkey FOREIGN KEY (created_by) REFERENCES ops_user(user_id)
constraint event_subscription event_subscription_event_types_check CHECK (((array_length(event_types, 1) >= 1) AND (array_length(event_types, 1) <= 3)))
constraint event_subscription event_subscription_geofence_id_fkey FOREIGN KEY (geofence_id) REFERENCES geofence(geofence_id) ON DELETE CASCADE
constraint event_subscription event_subscription_pkey PRIMARY KEY (subscription_id)
constraint event_subscription event_subscription_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint event_subscription event_subscription_tenant_id_name_key UNIQUE (tenant_id, name)
constraint geofence geofence_created_by_fkey FOREIGN KEY (created_by) REFERENCES ops_user(user_id)
constraint geofence geofence_pkey PRIMARY KEY (geofence_id)
constraint geofence geofence_shape_check CHECK (((shape)::text = ANY ((ARRAY['CIRCLE'::character varying, 'POLYGON'::character varying, 'ELLIPSE'::character varying, 'ARC'::character varying])::text[])))
constraint geofence geofence_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint geofence geofence_tenant_id_name_key UNIQUE (tenant_id, name)
constraint geofence_arm geofence_arm_armed_by_fkey FOREIGN KEY (armed_by) REFERENCES ops_user(user_id)
constraint geofence_arm geofence_arm_geofence_id_fkey FOREIGN KEY (geofence_id) REFERENCES geofence(geofence_id) ON DELETE CASCADE
constraint geofence_arm geofence_arm_pkey PRIMARY KEY (geofence_id, subject_ref)
constraint geofence_arm geofence_arm_state_check CHECK (((state)::text = ANY ((ARRAY['INSIDE'::character varying, 'OUTSIDE'::character varying])::text[])))
constraint geofence_arm geofence_arm_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint geofence_event geofence_event_event_type_check CHECK (((event_type)::text = ANY ((ARRAY['ENTER'::character varying, 'LEAVE'::character varying, 'INSIDE'::character varying])::text[])))
constraint geofence_event geofence_event_geofence_id_fkey FOREIGN KEY (geofence_id) REFERENCES geofence(geofence_id) ON DELETE CASCADE
constraint geofence_event geofence_event_geofence_id_subject_ref_result_id_event_type_key UNIQUE (geofence_id, subject_ref, result_id, event_type)
constraint geofence_event geofence_event_pkey PRIMARY KEY (event_id)
constraint geofence_event geofence_event_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint location_request location_request_client_id_fkey FOREIGN KEY (client_id) REFERENCES api_client(client_id)
constraint location_request location_request_path_check CHECK (((path)::text = ANY ((ARRAY['CELL_ID'::character varying, 'E_SMLC'::character varying, 'LMF'::character varying])::text[])))
constraint location_request location_request_pkey PRIMARY KEY (request_id)
constraint location_request location_request_rat_check CHECK (((rat)::text = ANY ((ARRAY['GSM'::character varying, 'UMTS'::character varying, 'LTE'::character varying, 'NR'::character varying])::text[])))
constraint location_request location_request_requested_accuracy_m_check CHECK ((requested_accuracy_m > 0))
constraint location_request location_request_requested_priority_check CHECK (((requested_priority)::text = ANY ((ARRAY['LOW'::character varying, 'NORMAL'::character varying, 'HIGH'::character varying])::text[])))
constraint location_request location_request_state_check CHECK (((state)::text = ANY ((ARRAY['RECEIVED'::character varying, 'AUTHORISED'::character varying, 'PRIVACY_CHECKED'::character varying, 'ROUTED'::character varying, 'REQUESTED'::character varying, 'ANSWERED'::character varying, 'TIMEOUT'::character varying, 'FAILED'::character varying, 'DELIVERED'::character varying])::text[])))
constraint location_request location_request_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint location_request request_has_a_subject CHECK (((subscriber_msisdn IS NOT NULL) OR (subscriber_imsi IS NOT NULL)))
constraint location_result location_result_age_seconds_check CHECK ((age_seconds >= 0))
constraint location_result location_result_confidence_check CHECK (((confidence >= 0) AND (confidence <= 100)))
constraint location_result location_result_engine_check CHECK (((engine)::text = ANY ((ARRAY['E_SMLC'::character varying, 'LMF'::character varying, 'SMLC'::character varying, 'CELL_DB'::character varying])::text[])))
constraint location_result location_result_geometry_data_quality_check CHECK (((geometry_data_quality >= 0) AND (geometry_data_quality <= 100)))
constraint location_result location_result_pkey PRIMARY KEY (result_id)
constraint location_result location_result_shape_check CHECK (((shape)::text = ANY ((ARRAY['POINT'::character varying, 'CIRCLE'::character varying, 'ELLIPSE'::character varying, 'POLYGON'::character varying, 'ARC'::character varying])::text[])))
constraint location_result location_result_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint location_result location_result_uncertainty_m_check CHECK ((uncertainty_m > 0))
constraint location_result result_geodetic CHECK (((longitude IS NULL) OR ((longitude >= ('-180'::integer)::numeric) AND (longitude <= (180)::numeric))))
constraint location_result result_range CHECK (((latitude IS NULL) OR ((latitude >= ('-90'::integer)::numeric) AND (latitude <= (90)::numeric))))
constraint notification_channel notification_channel_channel_type_check CHECK (((channel_type)::text = ANY ((ARRAY['WEBHOOK'::character varying, 'EMAIL'::character varying, 'TELEGRAM'::character varying, 'SLACK'::character varying])::text[])))
constraint notification_channel notification_channel_created_by_fkey FOREIGN KEY (created_by) REFERENCES ops_user(user_id)
constraint notification_channel notification_channel_pkey PRIMARY KEY (channel_id)
constraint notification_channel notification_channel_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint notification_channel notification_channel_tenant_id_name_key UNIQUE (tenant_id, name)
constraint ops_alert ops_alert_kind_check CHECK (((kind)::text = ANY ((ARRAY['PEER_DOWN'::character varying, 'ERROR_RATE'::character varying, 'LATENCY_SLO'::character varying, 'CELL_DB_GAP'::character varying])::text[])))
constraint ops_alert ops_alert_pkey PRIMARY KEY (tenant_id, alert_id)
constraint ops_alert ops_alert_severity_check CHECK (((severity)::text = ANY ((ARRAY['INFO'::character varying, 'WARNING'::character varying, 'CRITICAL'::character varying])::text[])))
constraint ops_alert ops_alert_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint ops_break_glass ops_break_glass_end_after_grant CHECK (((ended_at IS NULL) OR (ended_at >= granted_at)))
constraint ops_break_glass ops_break_glass_expiry_after_grant CHECK ((expires_at > granted_at))
constraint ops_break_glass ops_break_glass_pkey PRIMARY KEY (grant_id)
constraint ops_break_glass ops_break_glass_reason_present CHECK ((length(btrim((reason)::text)) >= 20))
constraint ops_break_glass ops_break_glass_review_pair CHECK ((((review_status)::text <> 'REVIEWED'::text) OR ((reviewed_by IS NOT NULL) AND (reviewed_at IS NOT NULL) AND (review_note IS NOT NULL))))
constraint ops_break_glass ops_break_glass_review_status_check CHECK (((review_status)::text = ANY ((ARRAY['PENDING'::character varying, 'REVIEWED'::character varying])::text[])))
constraint ops_break_glass ops_break_glass_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES ops_user(user_id)
constraint ops_break_glass ops_break_glass_reviewer_is_other CHECK (((reviewed_by IS NULL) OR (reviewed_by <> user_id)))
constraint ops_break_glass ops_break_glass_session_id_fkey FOREIGN KEY (session_id) REFERENCES ops_session(session_id) ON DELETE SET NULL
constraint ops_break_glass ops_break_glass_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint ops_break_glass ops_break_glass_user_id_fkey FOREIGN KEY (user_id) REFERENCES ops_user(user_id)
constraint ops_session ops_session_expiry_order CHECK ((idle_expires_at <= absolute_expires_at))
constraint ops_session ops_session_pkey PRIMARY KEY (session_id)
constraint ops_session ops_session_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint ops_session ops_session_token_hash_key UNIQUE (token_hash)
constraint ops_session ops_session_user_id_fkey FOREIGN KEY (user_id) REFERENCES ops_user(user_id)
constraint ops_user ops_user_failed_attempts_check CHECK ((failed_attempts >= 0))
constraint ops_user ops_user_pkey PRIMARY KEY (user_id)
constraint ops_user ops_user_role_check CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'OPERATOR'::character varying, 'ANALYST'::character varying, 'VIEWER'::character varying])::text[])))
constraint ops_user ops_user_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint ops_user ops_user_tenant_id_username_key UNIQUE (tenant_id, username)
constraint tenant tenant_pkey PRIMARY KEY (tenant_id)
constraint tracked_subject tracked_subject_client_id_fkey FOREIGN KEY (client_id) REFERENCES api_client(client_id)
constraint tracked_subject tracked_subject_created_by_fkey FOREIGN KEY (created_by) REFERENCES ops_user(user_id)
constraint tracked_subject tracked_subject_has_identifier CHECK (((subject_msisdn IS NOT NULL) OR (subject_imsi IS NOT NULL)))
constraint tracked_subject tracked_subject_pkey PRIMARY KEY (tracked_id)
constraint tracked_subject tracked_subject_purpose_check CHECK (((purpose)::text = ANY ((ARRAY['SAFETY'::character varying, 'FLEET_MANAGEMENT'::character varying, 'COMMERCIAL_LBS'::character varying, 'IOT_ASSET'::character varying])::text[])))
constraint tracked_subject tracked_subject_tenant_id_client_id_subject_ref_purpose_key UNIQUE (tenant_id, client_id, subject_ref, purpose)
constraint tracked_subject tracked_subject_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint tracking_run tracking_run_notify_state_check CHECK (((notify_state)::text = ANY ((ARRAY['NONE'::character varying, 'SENT'::character varying, 'FAILED'::character varying, 'NOT_CONFIGURED'::character varying])::text[])))
constraint tracking_run tracking_run_pkey PRIMARY KEY (run_id)
constraint tracking_run tracking_run_schedule_id_fkey FOREIGN KEY (schedule_id) REFERENCES tracking_schedule(schedule_id) ON DELETE CASCADE
constraint tracking_run tracking_run_state_check CHECK (((state)::text = ANY ((ARRAY['LOCATED'::character varying, 'NO_FIX'::character varying, 'SKIPPED'::character varying, 'FAILED'::character varying])::text[])))
constraint tracking_run tracking_run_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint tracking_run tracking_run_trigger_kind_check CHECK (((trigger_kind)::text = ANY ((ARRAY['SCHEDULE'::character varying, 'MANUAL'::character varying])::text[])))
constraint tracking_schedule tracking_schedule_accuracy_m_check CHECK (((accuracy_m IS NULL) OR ((accuracy_m >= 1) AND (accuracy_m <= 100000))))
constraint tracking_schedule tracking_schedule_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES notification_channel(channel_id) ON DELETE SET NULL
constraint tracking_schedule tracking_schedule_created_by_fkey FOREIGN KEY (created_by) REFERENCES ops_user(user_id)
constraint tracking_schedule tracking_schedule_interval_seconds_check CHECK (((interval_seconds >= 300) AND (interval_seconds <= 86400)))
constraint tracking_schedule tracking_schedule_notify_channel CHECK (((NOT notify) OR (channel_id IS NOT NULL)))
constraint tracking_schedule tracking_schedule_pkey PRIMARY KEY (schedule_id)
constraint tracking_schedule tracking_schedule_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
constraint tracking_schedule tracking_schedule_tenant_id_tracked_id_key UNIQUE (tenant_id, tracked_id)
constraint tracking_schedule tracking_schedule_tracked_id_fkey FOREIGN KEY (tracked_id) REFERENCES tracked_subject(tracked_id) ON DELETE CASCADE
constraint tracking_schedule tracking_schedule_window CHECK (((ends_at IS NULL) OR (ends_at > starts_at)))
constraint webhook_delivery webhook_delivery_event_id_fkey FOREIGN KEY (event_id) REFERENCES geofence_event(event_id) ON DELETE CASCADE
constraint webhook_delivery webhook_delivery_pkey PRIMARY KEY (delivery_id)
constraint webhook_delivery webhook_delivery_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'DELIVERED'::character varying, 'FAILED'::character varying])::text[])))
constraint webhook_delivery webhook_delivery_subscription_id_event_id_key UNIQUE (subscription_id, event_id)
constraint webhook_delivery webhook_delivery_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES event_subscription(subscription_id) ON DELETE CASCADE
constraint webhook_delivery webhook_delivery_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id)
index api_client api_client_key_hash_key CREATE UNIQUE INDEX api_client_key_hash_key ON api_client USING btree (key_hash)
index api_client api_client_pkey CREATE UNIQUE INDEX api_client_pkey ON api_client USING btree (client_id)
index api_client api_client_tenant_id_name_key CREATE UNIQUE INDEX api_client_tenant_id_name_key ON api_client USING btree (tenant_id, name)
index audit_event audit_actor_user CREATE INDEX audit_actor_user ON audit_event USING btree (tenant_id, actor_user_id, occurred_at)
index audit_event audit_event_pkey CREATE UNIQUE INDEX audit_event_pkey ON audit_event USING btree (tenant_id, audit_id)
index audit_event audit_request CREATE INDEX audit_request ON audit_event USING btree (tenant_id, request_id)
index audit_event audit_time CREATE INDEX audit_time ON audit_event USING btree (tenant_id, occurred_at)
index capability_profile capability_profile_pkey CREATE UNIQUE INDEX capability_profile_pkey ON capability_profile USING btree (tenant_id)
index cdr cdr_client CREATE INDEX cdr_client ON cdr USING btree (tenant_id, client_id, created_at)
index cdr cdr_pkey CREATE UNIQUE INDEX cdr_pkey ON cdr USING btree (tenant_id, cdr_id)
index cdr cdr_protocol_time CREATE INDEX cdr_protocol_time ON cdr USING btree (tenant_id, protocol, created_at)
index cdr cdr_request CREATE INDEX cdr_request ON cdr USING btree (tenant_id, request_id)
index cdr cdr_time CREATE INDEX cdr_time ON cdr USING btree (tenant_id, created_at)
index cell cell_area CREATE INDEX cell_area ON cell USING btree (tenant_id, rat, area_code)
index cell cell_pkey CREATE UNIQUE INDEX cell_pkey ON cell USING btree (tenant_id, rat, cell_identity)
index client_usage client_usage_pkey CREATE UNIQUE INDEX client_usage_pkey ON client_usage USING btree (tenant_id, client_id, window_kind, window_start)
index client_usage client_usage_sweep CREATE INDEX client_usage_sweep ON client_usage USING btree (window_start)
index config_change config_change_one_pending CREATE UNIQUE INDEX config_change_one_pending ON config_change USING btree (tenant_id, item_key) WHERE ((status)::text = 'PENDING'::text)
index config_change config_change_pkey CREATE UNIQUE INDEX config_change_pkey ON config_change USING btree (change_id)
index config_change config_change_time CREATE INDEX config_change_time ON config_change USING btree (tenant_id, requested_at DESC)
index config_item config_item_pkey CREATE UNIQUE INDEX config_item_pkey ON config_item USING btree (tenant_id, item_key)
index consent consent_active_unique CREATE UNIQUE INDEX consent_active_unique ON consent USING btree (tenant_id, subject_ref, purpose, COALESCE(client_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'GRANTED'::character varying])::text[]))
index consent consent_pkey CREATE UNIQUE INDEX consent_pkey ON consent USING btree (consent_id)
index consent consent_subject CREATE INDEX consent_subject ON consent USING btree (tenant_id, subject_ref, purpose)
index event_subscription event_subscription_pkey CREATE UNIQUE INDEX event_subscription_pkey ON event_subscription USING btree (subscription_id)
index event_subscription event_subscription_tenant CREATE INDEX event_subscription_tenant ON event_subscription USING btree (tenant_id, enabled)
index event_subscription event_subscription_tenant_id_name_key CREATE UNIQUE INDEX event_subscription_tenant_id_name_key ON event_subscription USING btree (tenant_id, name)
index geofence geofence_pkey CREATE UNIQUE INDEX geofence_pkey ON geofence USING btree (geofence_id)
index geofence geofence_tenant CREATE INDEX geofence_tenant ON geofence USING btree (tenant_id, enabled, group_name)
index geofence geofence_tenant_id_name_key CREATE UNIQUE INDEX geofence_tenant_id_name_key ON geofence USING btree (tenant_id, name)
index geofence_arm geofence_arm_pkey CREATE UNIQUE INDEX geofence_arm_pkey ON geofence_arm USING btree (geofence_id, subject_ref)
index geofence_arm geofence_arm_state CREATE INDEX geofence_arm_state ON geofence_arm USING btree (tenant_id, state)
index geofence_arm geofence_arm_subject CREATE INDEX geofence_arm_subject ON geofence_arm USING btree (tenant_id, subject_ref)
index geofence_event geofence_event_geofence CREATE INDEX geofence_event_geofence ON geofence_event USING btree (geofence_id, occurred_at DESC)
index geofence_event geofence_event_geofence_id_subject_ref_result_id_event_type_key CREATE UNIQUE INDEX geofence_event_geofence_id_subject_ref_result_id_event_type_key ON geofence_event USING btree (geofence_id, subject_ref, result_id, event_type)
index geofence_event geofence_event_pkey CREATE UNIQUE INDEX geofence_event_pkey ON geofence_event USING btree (event_id)
index geofence_event geofence_event_range CREATE INDEX geofence_event_range ON geofence_event USING btree (tenant_id, occurred_at DESC)
index geofence_event geofence_event_type CREATE INDEX geofence_event_type ON geofence_event USING btree (tenant_id, event_type, occurred_at DESC)
index location_request location_request_pkey CREATE UNIQUE INDEX location_request_pkey ON location_request USING btree (request_id)
index location_request request_client_time CREATE INDEX request_client_time ON location_request USING btree (tenant_id, client_id, created_at)
index location_request request_idempotency CREATE UNIQUE INDEX request_idempotency ON location_request USING btree (tenant_id, client_id, idempotency_key) WHERE (idempotency_key IS NOT NULL)
index location_request request_open CREATE INDEX request_open ON location_request USING btree (tenant_id, state, deadline_at)
index location_request request_protocol_time CREATE INDEX request_protocol_time ON location_request USING btree (tenant_id, protocol, created_at)
index location_request request_subject CREATE INDEX request_subject ON location_request USING btree (tenant_id, subscriber_imsi, created_at)
index location_request request_tracked_imsi CREATE INDEX request_tracked_imsi ON location_request USING btree (tenant_id, client_id, purpose, subscriber_imsi, created_at)
index location_request request_tracked_msisdn CREATE INDEX request_tracked_msisdn ON location_request USING btree (tenant_id, client_id, purpose, subscriber_msisdn, created_at)
index location_result location_result_pkey CREATE UNIQUE INDEX location_result_pkey ON location_result USING btree (result_id)
index location_result result_request CREATE INDEX result_request ON location_result USING btree (tenant_id, request_id)
index location_result result_time CREATE INDEX result_time ON location_result USING btree (tenant_id, produced_at)
index notification_channel notification_channel_pkey CREATE UNIQUE INDEX notification_channel_pkey ON notification_channel USING btree (channel_id)
index notification_channel notification_channel_tenant CREATE INDEX notification_channel_tenant ON notification_channel USING btree (tenant_id, enabled)
index notification_channel notification_channel_tenant_id_name_key CREATE UNIQUE INDEX notification_channel_tenant_id_name_key ON notification_channel USING btree (tenant_id, name)
index ops_alert ops_alert_open_unique CREATE UNIQUE INDEX ops_alert_open_unique ON ops_alert USING btree (tenant_id, dedupe_key) WHERE (closed_at IS NULL)
index ops_alert ops_alert_pkey CREATE UNIQUE INDEX ops_alert_pkey ON ops_alert USING btree (tenant_id, alert_id)
index ops_alert ops_alert_time CREATE INDEX ops_alert_time ON ops_alert USING btree (tenant_id, closed_at, opened_at DESC)
index ops_break_glass ops_break_glass_one_active CREATE UNIQUE INDEX ops_break_glass_one_active ON ops_break_glass USING btree (tenant_id, user_id) WHERE (ended_at IS NULL)
index ops_break_glass ops_break_glass_pkey CREATE UNIQUE INDEX ops_break_glass_pkey ON ops_break_glass USING btree (grant_id)
index ops_break_glass ops_break_glass_session CREATE INDEX ops_break_glass_session ON ops_break_glass USING btree (session_id)
index ops_break_glass ops_break_glass_time CREATE INDEX ops_break_glass_time ON ops_break_glass USING btree (tenant_id, granted_at DESC)
index ops_session ops_session_pkey CREATE UNIQUE INDEX ops_session_pkey ON ops_session USING btree (session_id)
index ops_session ops_session_sweep CREATE INDEX ops_session_sweep ON ops_session USING btree (absolute_expires_at)
index ops_session ops_session_token_hash_key CREATE UNIQUE INDEX ops_session_token_hash_key ON ops_session USING btree (token_hash)
index ops_session ops_session_user CREATE INDEX ops_session_user ON ops_session USING btree (tenant_id, user_id, revoked_at)
index ops_user ops_user_pkey CREATE UNIQUE INDEX ops_user_pkey ON ops_user USING btree (user_id)
index ops_user ops_user_role CREATE INDEX ops_user_role ON ops_user USING btree (tenant_id, role, enabled)
index ops_user ops_user_tenant_id_username_key CREATE UNIQUE INDEX ops_user_tenant_id_username_key ON ops_user USING btree (tenant_id, username)
index tenant tenant_pkey CREATE UNIQUE INDEX tenant_pkey ON tenant USING btree (tenant_id)
index tracked_subject tracked_subject_client CREATE INDEX tracked_subject_client ON tracked_subject USING btree (tenant_id, client_id)
index tracked_subject tracked_subject_pkey CREATE UNIQUE INDEX tracked_subject_pkey ON tracked_subject USING btree (tracked_id)
index tracked_subject tracked_subject_tenant CREATE INDEX tracked_subject_tenant ON tracked_subject USING btree (tenant_id, enabled, purpose)
index tracked_subject tracked_subject_tenant_id_client_id_subject_ref_purpose_key CREATE UNIQUE INDEX tracked_subject_tenant_id_client_id_subject_ref_purpose_key ON tracked_subject USING btree (tenant_id, client_id, subject_ref, purpose)
index tracking_run tracking_run_pkey CREATE UNIQUE INDEX tracking_run_pkey ON tracking_run USING btree (run_id)
index tracking_run tracking_run_schedule CREATE INDEX tracking_run_schedule ON tracking_run USING btree (schedule_id, occurred_at DESC)
index tracking_schedule tracking_schedule_due CREATE INDEX tracking_schedule_due ON tracking_schedule USING btree (enabled, next_run_at)
index tracking_schedule tracking_schedule_pkey CREATE UNIQUE INDEX tracking_schedule_pkey ON tracking_schedule USING btree (schedule_id)
index tracking_schedule tracking_schedule_tenant_id_tracked_id_key CREATE UNIQUE INDEX tracking_schedule_tenant_id_tracked_id_key ON tracking_schedule USING btree (tenant_id, tracked_id)
index webhook_delivery webhook_delivery_due CREATE INDEX webhook_delivery_due ON webhook_delivery USING btree (status, next_attempt_at)
index webhook_delivery webhook_delivery_pkey CREATE UNIQUE INDEX webhook_delivery_pkey ON webhook_delivery USING btree (delivery_id)
index webhook_delivery webhook_delivery_subscription CREATE INDEX webhook_delivery_subscription ON webhook_delivery USING btree (subscription_id, created_at DESC)
index webhook_delivery webhook_delivery_subscription_id_event_id_key CREATE UNIQUE INDEX webhook_delivery_subscription_id_event_id_key ON webhook_delivery USING btree (subscription_id, event_id)
sequence audit_event_audit_id_seq bigint start=1 min=1 max=9223372036854775807 increment=1
sequence cdr_cdr_id_seq bigint start=1 min=1 max=9223372036854775807 increment=1
sequence geofence_event_event_id_seq bigint start=1 min=1 max=9223372036854775807 increment=1
sequence ops_alert_alert_id_seq bigint start=1 min=1 max=9223372036854775807 increment=1
sequence tracking_run_run_id_seq bigint start=1 min=1 max=9223372036854775807 increment=1
sequence webhook_delivery_delivery_id_seq bigint start=1 min=1 max=9223372036854775807 increment=1
table api_client
table audit_event
table capability_profile
table cdr
table cell
table client_usage
table config_change
table config_item
table consent
table event_subscription
table geofence
table geofence_arm
table geofence_event
table location_request
table location_result
table notification_channel
table ops_alert
table ops_break_glass
table ops_session
table ops_user
table tenant
table tracked_subject
table tracking_run
table tracking_schedule
table webhook_delivery
