#include <Rcpp.h>
#include <cmath>
#include <iostream>
#include <unordered_map>
#include <utility>

using namespace Rcpp;

// TODO Double key for k shouldn't be necessary but does something weird when
// you put in in integer and just returns the same result repeatedly
using Cache = std::unordered_map<double,double>;

double fast_C(int n, double k, double pi,Cache &cache) {


    auto search = cache.find(k);

    if(search!=cache.end()){
        return cache[k];
    }

  if (k == 0 || k == n) {
    return 1 / std::pow(2, n);
  } else {
    double t1 = 1 / std::sqrt((2 * pi));
    double t2 = std::sqrt(n / ((n - k) * k));
    double t3 =
        std::exp(n * std::log(n / (2 * (n - k))) + k * std::log((n - k) / k));

    auto result = t1 * t2 * t3;
    cache[k] = result;
    return result;
  }
}

double
fast_get_probability_constraint_violation(int num_constraint_coefficients,
                                          double constraint_budget, double pi,Cache &cache) {

  int n = num_constraint_coefficients;
  double gamma = constraint_budget;
  double v = (gamma + n) / 2;
  double mu = v - std::floor(v);

  double t1 = (1 - mu) * fast_C(n, std::floor(v),pi,cache);
  double k = std::floor(v) + 1;
  double t2 = 0;

  if (k <= n) {
    while (k <= n) {
      t2 += fast_C(n, k, pi,cache);
      k++;
    }
  }

  return t1 + t2;
}

// [[Rcpp::export]]
NumericVector
get_all_probability_constraint_violation(int num_constraint_coefficients,
                                         NumericVector budget, double pi) {

  Cache cache;
  NumericVector out(budget.size());
  for (int i = 0; i < budget.size(); ++i) {
    auto result = fast_get_probability_constraint_violation(
        num_constraint_coefficients, budget[i], pi,cache);

    out[i] = result;

  }

  return out;
}

