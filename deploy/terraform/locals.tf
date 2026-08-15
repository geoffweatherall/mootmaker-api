locals {
  # Every AWS resource name derives from this instead of project_name directly,
  # so multiple environments can coexist in the same AWS account without
  # colliding (e.g. test-mootmaker-rooms vs production-mootmaker-rooms).
  resource_prefix = "${var.environment}-${var.project_name}"
}
