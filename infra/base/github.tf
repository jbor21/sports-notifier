# CI pushes images without stored AWS keys (spec section 6). GitHub signs a short-lived identity token
# for each workflow run; AWS trusts GitHub's signer (the OIDC provider) and swaps the token for this
# role's credentials for one hour, but only for this repository's main branch and its pull requests.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "github_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # "sub" names the repository and what triggered the run. Not a wildcard: a workflow on any other
    # branch, or in any other repository, gets nothing.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:boboborenstein-collab/sports-notifier:ref:refs/heads/main",
        "repo:boboborenstein-collab/sports-notifier:pull_request",
      ]
    }
  }
}

resource "aws_iam_role" "github" {
  name               = "sports-notifier-github"
  assume_role_policy = data.aws_iam_policy_document.github_trust.json
}

# Push to the one repository, and nothing else.
data "aws_iam_policy_document" "github_push" {
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # this action has no resource; it logs Docker in to the registry
  }
  statement {
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
    ]
    resources = [aws_ecr_repository.app.arn]
  }
}

resource "aws_iam_role_policy" "github_push" {
  name   = "push-sports-notifier-image"
  role   = aws_iam_role.github.id
  policy = data.aws_iam_policy_document.github_push.json
}
