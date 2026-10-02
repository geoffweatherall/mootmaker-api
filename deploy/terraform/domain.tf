# Custom domain for the AppSync API. "production" deploys to api.mootmaker.com;
# every other environment gets api.<environment>.mootmaker.com. A single
# wildcard certificate can't cover the latter for an arbitrary environment
# name (ACM/CloudFront wildcards only match one subdomain level), so this
# environment provisions and DNS-validates its own certificate for exactly
# its own hostname - see mootmaker-domain's README for the full reasoning.
# Requires mootmaker-domain to already be deployed, and its nameservers
# configured at the registrar with delegation propagated (DNS validation
# below queries mootmaker.com's authoritative nameservers).
locals {
  api_domain = var.environment == "production" ? "api.mootmaker.com" : "api.${var.environment}.mootmaker.com"
}

data "aws_route53_zone" "this" {
  name = "mootmaker.com."
}

resource "aws_acm_certificate" "api" {
  provider          = aws.us_east_1
  domain_name       = local.api_domain
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "api_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.api.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id = data.aws_route53_zone.this.zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60
}

resource "aws_acm_certificate_validation" "api" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.api.arn
  validation_record_fqdns = [for record in aws_route53_record.api_cert_validation : record.fqdn]
}

resource "aws_appsync_domain_name" "this" {
  domain_name     = local.api_domain
  certificate_arn = aws_acm_certificate_validation.api.certificate_arn
}

resource "aws_appsync_domain_name_api_association" "this" {
  api_id      = aws_appsync_graphql_api.this.id
  domain_name = aws_appsync_domain_name.this.domain_name
}

resource "aws_route53_record" "api_alias" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = local.api_domain
  type    = "A"

  alias {
    name                   = aws_appsync_domain_name.this.appsync_domain_name
    zone_id                = aws_appsync_domain_name.this.hosted_zone_id
    evaluate_target_health = false
  }
}

# IPv6 twin of the A alias above. Without it every IPv6-capable resolver's AAAA lookup got a
# "no such record" answer, which Route 53 bills - alias answers are free - and then fell back to IPv4.
resource "aws_route53_record" "api_alias_ipv6" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = local.api_domain
  type    = "AAAA"

  alias {
    name                   = aws_appsync_domain_name.this.appsync_domain_name
    zone_id                = aws_appsync_domain_name.this.hosted_zone_id
    evaluate_target_health = false
  }
}
