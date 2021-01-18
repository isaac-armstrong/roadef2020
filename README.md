Each year there is an international competition to solve an optimization problem submitted by a company. The 2020 challenge was to determine how to optimally allocate manpower to maintain the electrical grid while managing the risk of blackouts. This required juggling multiple objectives and optimizing a problem where data parameters were uncertain because they were the product of multiple forecasts and simulations. An integer programming based robust optimization approach leveraging Gurobi is implemented in Java while post-processing and pre-processing is conducted in both R,Python, and C++. A grid search is used to select hyperparameters for the optimization model.

This repository depends on the competition repository: https://github.com/rte-france/challenge-roadef-2020

The presentation can be found in https://github.com/isaac-armstrong/roadef2020/blob/main/isaac_armstrong_or_presentation.pdf
